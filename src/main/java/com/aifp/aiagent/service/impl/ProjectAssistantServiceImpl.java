package com.aifp.aiagent.service.impl;

import com.aifp.aiagent.assistant.*;
import com.aifp.aiagent.common.ResultCode;
import com.aifp.aiagent.dto.*;
import com.aifp.aiagent.exception.BusinessException;
import com.aifp.aiagent.rag.ProjectRetrievalService;
import com.aifp.aiagent.service.ProjectAssistantService;
import com.aifp.aiagent.service.ProjectQueryService;
import com.aifp.aiagent.service.ProjectService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.document.Document;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * 项目助手服务实现（需求 §二十八编排式流程；Phase 9 增加会话隔离）
 * <p>
 * 执行序：参数防御 → ProjectService 守门（403/6001 唯一入口，权限前置——
 * 守门通过前零 Redis 访问）→ 会话历史读取（ConversationHistoryStore，降级安全）→
 * QueryIntentAnalyzer（第一次 LLM：意图 + 指代消解改写，仅注入历史用户问题）→
 * UNSUPPORTED 短路（追加约束 5，Phase 10 文案收窄为归因分析）→ QueryPlanner 按
 * AssistantQueryPlan 拉取结构化事实/项目 RAG（query=standaloneQuestion）/文件清单 →
 * AssistantPromptBuilder（反伪造 System Prompt + 对话历史段）→ ChatModel 最终回答 →
 * 组装 Response → appendTurn（后置：写失败不影响已生成的回答）。
 * <p>
 * Phase 10 COMPARISON 分支：比较编排（槽位 LLM + 名称→ID + 确定性计算）先行，
 * 降级零后续 LLM 零检索；成功后 RAG 仅解释依据，比较证据并入 references/usedFiles/
 * usedProjects，comparisonData 单独返回。
 * <p>
 * 硬边界：不解析业务数字（追加约束 10）；不直查 Mapper（Phase 7 约束 3）；
 * 历史不是事实来源——项目事实每轮必须重新从本次 ProjectQueryService/
 * ProjectRetrievalService 拉取（Phase 9 追加约束 1）；引用为"可使用的证据集合"
 * 而非 LLM 实际引用（追加约束 3，Phase 11 精修 citation）。
 *
 * @author Tang_tzb
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ProjectAssistantServiceImpl implements ProjectAssistantService {

    /**
     * 文件清单拉取页大小（追加约束 7：PageQuery pageSize 上限 100，这是<b>当前版本
     * 限制</b>——超出 100 个文件的项目清单不完整，不构成业务上永远完整的承诺；
     * 后续 Phase 可扩展分页参数或专门的非分页查询）
     */
    private static final int FILE_LIST_PAGE_SIZE = 100;

    private final ProjectService projectService;
    private final ProjectQueryService projectQueryService;
    private final ProjectRetrievalService projectRetrievalService;
    private final QueryIntentAnalyzer queryIntentAnalyzer;
    private final QueryPlanner queryPlanner;
    private final AssistantPromptBuilder promptBuilder;
    private final ChunkMetadataReader chunkMetadataReader;
    private final ConversationHistoryStore conversationHistoryStore;
    private final ProjectComparisonService comparisonService;
    private final ChatModel chatModel;

    /**
     * 助手 RAG 检索条数（独立于抽取链 rag.retrieve.topK）
     */
    @Value("${assistant.retrieve.topK:5}")
    private int assistantTopK;

    /**
     * 注入意图识别/回答 Prompt 的最近轮次（追加约束 3：与 Redis 存储侧
     * max-stored-turns 相互独立，禁止把存储的 20 轮全量塞给 LLM）
     */
    @Value("${assistant.conversation.max-injected-turns:6}")
    private int maxInjectedTurns;

    @Override
    public AssistantChatResponse chat(Long projectId, AssistantChatRequest request) {
        // 参数防御（@NotBlank 之外的兜底，直调场景可达）
        if (request.getMessage() == null || request.getMessage().isBlank()) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "提问内容不能为空");
        }
        String conversationId = resolveConversationId(request.getConversationId());
        // 权限/存在性守门唯一入口（403/6001）；守门通过前零 Redis 访问（追加约束 6）
        ProjectVO project = projectService.getProjectById(projectId);
        // 会话历史（守门后读取，Redis 异常降级无历史）：时间顺序，最近一轮在最后（追加约束 3）
        List<ConversationTurn> history = conversationHistoryStore
                .loadRecentTurns(projectId, conversationId, maxInjectedTurns);
        List<String> recentQuestions = history.stream()
                .map(ConversationTurn::getUserQuestion).toList();
        AssistantIntentAnalysis analysis = queryIntentAnalyzer
                .analyze(request.getMessage(), project.getProjectName(), recentQuestions);
        // UNSUPPORTED 真短路（追加约束 5/14）：意图识别后立即返回，禁止任何检索/事实查询
        if (analysis.getIntent() == AssistantIntent.UNSUPPORTED) {
            AssistantChatResponse response = buildUnsupportedResponse(projectId, conversationId);
            // 追加约束 5：只记录真实交互（assistantAnswer=实际返回的固定文案）
            conversationHistoryStore.appendTurn(projectId, conversationId,
                    request.getMessage(), response.getAnswer());
            return response;
        }
        return answer(projectId, conversationId, request.getMessage(),
                project.getProjectName(), analysis, history);
    }

    // ==================== 内部方法 ====================

    /**
     * 执行回答主链路：按计划拉数 → 组装 Context → 第二次 LLM → 响应组装 →
     * 会话记录（后置）。
     */
    private AssistantChatResponse answer(Long projectId, String conversationId, String message,
                                         String projectName, AssistantIntentAnalysis analysis,
                                         List<ConversationTurn> history) {
        // 追加约束 2：standaloneQuestion 是本轮唯一有效问题，RAG query 与 Prompt 用户问题共用
        String effectiveQuestion = analysis.getStandaloneQuestion();
        AssistantQueryPlan plan = queryPlanner.buildPlan(analysis.getIntent());
        if (analysis.getIntent() == AssistantIntent.COMPARISON) {
            return answerComparison(projectId, conversationId, message, projectName,
                    effectiveQuestion, history);
        }
        ProjectStructuredFactsVO facts = plan.isFetchFacts()
                ? projectQueryService.queryProjectFacts(projectId) : null;
        List<Document> documents = plan.isFetchRag()
                ? projectRetrievalService.retrieve(projectId, effectiveQuestion, assistantTopK) : List.of();
        List<String> fileNames = plan.isFetchFiles() ? fetchFileNames(projectId) : List.of();
        ProjectAssistantContext ctx = ProjectAssistantContext.builder()
                .projectName(projectName)
                .question(effectiveQuestion)
                .history(history)
                .facts(facts)
                .documents(documents)
                .fileNames(fileNames)
                .factsUsed(facts != null)
                .ragUsed(plan.isFetchRag())
                .filesUsed(plan.isFetchFiles())
                .build();
        String answerText = generateAnswer(ctx);
        AssistantChatResponse response = assembleResponse(projectId, conversationId, answerText, ctx);
        // 追加约束 4：appendTurn 后置——Response 已构造完成，Redis 写失败仅 warn 不影响返回
        conversationHistoryStore.appendTurn(projectId, conversationId, message, answerText);
        log.info("项目助手问答完成 projectId={}, intent={}, history={}, facts={}, rag={}, files={}, references={}",
                projectId, analysis.getIntent(), history.size(), facts != null, plan.isFetchRag(),
                plan.isFetchFiles(), response.getReferences().size());
        return response;
    }

    /**
     * COMPARISON 分支（Phase 10）：先执行跨项目比较编排（槽位 LLM + 名称→ID 映射 +
     * 字段级事实 + 确定性计算，追加约束 9/14——每次比较都重新查询与计算，历史不参与）。
     * 降级（字段未定/范围未定/无数据等，追加约束 2/12）→ 零后续 LLM 零检索直接返回
     * 确定性文案；成功 → RAG 仅作解释依据（追加约束 11），最终回答 LLM 只能引用
     * FieldComparisonCalculator 已算结果（追加约束 6）。
     */
    private AssistantChatResponse answerComparison(Long projectId, String conversationId, String message,
                                                   String projectName, String effectiveQuestion,
                                                   List<ConversationTurn> history) {
        ProjectComparisonService.ComparisonOutcome outcome =
                comparisonService.compare(projectId, effectiveQuestion);
        if (outcome.deterministicAnswer() != null) {
            AssistantChatResponse response = new AssistantChatResponse();
            response.setConversationId(conversationId);
            response.setAnswer(outcome.deterministicAnswer());
            response.setUsedProjects(List.of(projectId));
            conversationHistoryStore.appendTurn(projectId, conversationId, message, response.getAnswer());
            log.info("跨项目比较确定性降级 projectId={}", projectId);
            return response;
        }
        List<Document> documents = projectRetrievalService
                .retrieve(projectId, effectiveQuestion, assistantTopK);
        ProjectAssistantContext ctx = ProjectAssistantContext.builder()
                .projectName(projectName)
                .question(effectiveQuestion)
                .history(history)
                .comparison(outcome.comparison())
                .documents(documents)
                .ragUsed(true)
                .build();
        String answerText = generateAnswer(ctx);
        AssistantChatResponse response = assembleResponse(projectId, conversationId, answerText, ctx);
        response.setComparisonData(outcome.comparison());
        collectComparisonEvidence(outcome.comparison(), response);
        conversationHistoryStore.appendTurn(projectId, conversationId, message, answerText);
        log.info("跨项目比较完成 projectId={}, targets={}, units={}, excluded={}, references={}",
                projectId, outcome.comparison().getTargetProjectIds().size(),
                outcome.comparison().getUnits().size(),
                outcome.comparison().getExcluded().size(), response.getReferences().size());
        return response;
    }

    /**
     * 比较证据并入响应：参与/排除单元的全部来源作为结构化证据项（references 语义 =
     * "本次注入 LLM 的证据集合"，追加约束 3；excluded 明细同样进入 Prompt，不得静默
     * 丢弃，追加约束 5），来源文件并入 usedFiles；usedProjects = 当前项目 ∪ 目标项目。
     * <p>
     * 证据顺序：比较证据（STRUCTURED）前置、RAG 命中（FILE）在后——比较是回答主依据，
     * RAG 仅解释依据（追加约束 11）；usedFiles 同样以比较来源优先去重。
     */
    private void collectComparisonEvidence(CrossProjectComparisonVO comparison,
                                           AssistantChatResponse response) {
        List<AssistantReferenceVO> comparisonRefs = new ArrayList<>();
        Set<Long> comparisonFileIds = new LinkedHashSet<>();
        for (CrossProjectComparisonVO.Unit unit : comparison.getUnits()) {
            comparisonRefs.add(toComparisonReference(comparison, unit));
            if (unit.getSourceFileId() != null) {
                comparisonFileIds.add(unit.getSourceFileId());
            }
        }
        for (CrossProjectComparisonVO.ExcludedUnit excluded : comparison.getExcluded()) {
            for (ProjectStructuredFactsVO.ValueItem item : excluded.getValues()) {
                comparisonRefs.add(toExcludedValueReference(comparison, item));
                if (item.getSourceFileId() != null) {
                    comparisonFileIds.add(item.getSourceFileId());
                }
            }
        }
        List<AssistantReferenceVO> references = response.getReferences();
        references.addAll(0, comparisonRefs);
        response.setReferences(references);
        // usedFiles：比较来源在前，RAG 命中在后去重（LinkedHashSet 保序）
        Set<Long> usedFileIds = new LinkedHashSet<>(comparisonFileIds);
        usedFileIds.addAll(response.getUsedFiles());
        response.setUsedFiles(new ArrayList<>(usedFileIds));
        Set<Long> projectIds = new TreeSet<>();
        projectIds.add(comparison.getCurrentProjectId());
        projectIds.addAll(comparison.getTargetProjectIds());
        response.setUsedProjects(new ArrayList<>(projectIds));
    }

    /**
     * 比较参与单元 → STRUCTURED 证据项（fieldCode/fieldName 取比较结果级快照）。
     */
    private AssistantReferenceVO toComparisonReference(CrossProjectComparisonVO comparison,
                                                       CrossProjectComparisonVO.Unit unit) {
        AssistantReferenceVO ref = new AssistantReferenceVO();
        ref.setType(AssistantReferenceVO.TYPE_STRUCTURED);
        ref.setFieldCode(comparison.getFieldCode());
        ref.setFieldName(comparison.getFieldName());
        ref.setRawValue(unit.getRawValue());
        ref.setNormalizedValue(unit.getNormalizedValue());
        ref.setUnit(unit.getUnit());
        ref.setSourceFileId(unit.getSourceFileId());
        ref.setSourceFileName(unit.getSourceFileName());
        ref.setSourcePage(unit.getSourcePage());
        ref.setSourceChunkId(unit.getSourceChunkId());
        return ref;
    }

    /**
     * 排除单元来源值 → STRUCTURED 证据项（fieldCode/fieldName 复用比较结果级快照，
     * excluded 单元与比较结果恒为同一 fieldCode）。
     */
    private AssistantReferenceVO toExcludedValueReference(CrossProjectComparisonVO comparison,
                                                          ProjectStructuredFactsVO.ValueItem item) {
        AssistantReferenceVO ref = new AssistantReferenceVO();
        ref.setType(AssistantReferenceVO.TYPE_STRUCTURED);
        ref.setFieldCode(comparison.getFieldCode());
        ref.setFieldName(comparison.getFieldName());
        ref.setRawValue(item.getRawValue());
        ref.setNormalizedValue(item.getNormalizedValue());
        ref.setUnit(item.getUnit());
        ref.setSourceFileId(item.getSourceFileId());
        ref.setSourceFileName(item.getSourceFileName());
        ref.setSourcePage(item.getSourcePage());
        ref.setSourceChunkId(item.getSourceChunkId());
        return ref;
    }

    /**
     * 第二次 LLM 调用（最终回答）。错误边界（追加约束 11）：调用异常或空白回答
     * 一律 3001 上抛，禁止降级 UNKNOWN 或重进 RAG 形成不可控循环。
     */
    private String generateAnswer(ProjectAssistantContext ctx) {
        try {
            Prompt prompt = new Prompt(List.of(
                    new SystemMessage(promptBuilder.buildSystemPrompt()),
                    new UserMessage(promptBuilder.buildUserPrompt(ctx))));
            ChatResponse response = chatModel.call(prompt);
            String text = response.getResult().getOutput().getText();
            if (text == null || text.isBlank()) {
                throw new BusinessException(ResultCode.AI_INVOKE_ERROR, "AI 未返回有效回答");
            }
            return text;
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            log.error("项目助手 AI 回答调用失败: {}", e.getMessage(), e);
            throw new BusinessException(ResultCode.AI_INVOKE_ERROR, "AI 回答调用失败");
        }
    }

    /**
     * 组装响应：references = 本次注入的全部结构化来源 ∪ 全部 RAG 命中
     * （"可使用的证据集合"，非 LLM 实际引用，追加约束 3）；usedFiles = 两来源
     * fileId 去重；structuredData = 本次注入 LLM 的字段事实；全部后端组装（追加约束 12）。
     */
    private AssistantChatResponse assembleResponse(Long projectId, String conversationId,
                                                   String answerText, ProjectAssistantContext ctx) {
        AssistantChatResponse response = new AssistantChatResponse();
        response.setConversationId(conversationId);
        response.setAnswer(answerText);
        List<AssistantReferenceVO> references = new ArrayList<>();
        Set<Long> usedFileIds = new LinkedHashSet<>();
        collectStructuredReferences(ctx, references, usedFileIds);
        collectFileReferences(ctx, references, usedFileIds);
        response.setReferences(references);
        response.setUsedFiles(new ArrayList<>(usedFileIds));
        response.setUsedProjects(List.of(projectId));
        response.setStructuredData(ctx.isFactsUsed() ? collectFields(ctx.getFacts()) : new ArrayList<>());
        return response;
    }

    /**
     * 收集结构化来源证据：每个 Field 的每条 ValueItem 独立成项（冲突多值全保留，
     * 硬约束 ⑧），并把来源文件并入 usedFiles。
     */
    private void collectStructuredReferences(ProjectAssistantContext ctx,
                                             List<AssistantReferenceVO> references,
                                             Set<Long> usedFileIds) {
        if (!ctx.isFactsUsed() || ctx.getFacts() == null) {
            return;
        }
        for (ProjectStructuredFactsVO.Form form : ctx.getFacts().getForms()) {
            for (ProjectStructuredFactsVO.Field field : form.getFields()) {
                for (ProjectStructuredFactsVO.ValueItem item : field.getValues()) {
                    references.add(toStructuredReference(field, item));
                    if (item.getSourceFileId() != null) {
                        usedFileIds.add(item.getSourceFileId());
                    }
                }
            }
        }
    }

    /**
     * 收集 RAG 命中文档证据：metadata 经 ChunkMetadataReader 安全解析，
     * 非法/缺失值如实置 null 不猜测（硬约束 ⑦）。
     */
    private void collectFileReferences(ProjectAssistantContext ctx,
                                       List<AssistantReferenceVO> references,
                                       Set<Long> usedFileIds) {
        if (!ctx.isRagUsed()) {
            return;
        }
        for (Document doc : ctx.getDocuments()) {
            ChunkMetadataReader.ChunkSource source = chunkMetadataReader.read(doc);
            references.add(toFileReference(source, doc.getId()));
            if (source.fileId() != null) {
                usedFileIds.add(source.fileId());
            }
        }
    }

    /**
     * 拉取项目文件名清单（追加约束 7：PageQuery(1,100) 为当前版本限制，非完整清单承诺）。
     */
    private List<String> fetchFileNames(Long projectId) {
        PageQuery query = new PageQuery();
        query.setPageNum(1);
        query.setPageSize(FILE_LIST_PAGE_SIZE);
        PageResult<FileRecordVO> page = projectService.listProjectFiles(projectId, query);
        return page.getRecords().stream().map(FileRecordVO::getFileName).toList();
    }

    /**
     * UNSUPPORTED 确定性响应（Phase 10 文案收窄，追加约束 13）：比较/排名/统计聚合
     * 已由 COMPARISON 意图承接，本短路仅覆盖归因分析、原因解释类问题；零 LLM 零检索，
     * 仅用于告知能力边界，不产生任何证据项；文案即实际返回内容，供 chat() 记入历史
     * （追加约束 5）。
     */
    private AssistantChatResponse buildUnsupportedResponse(Long projectId, String conversationId) {
        AssistantChatResponse response = new AssistantChatResponse();
        response.setConversationId(conversationId);
        response.setAnswer("当前版本暂不支持项目之间的归因分析与原因解释，请调整问题后重试。");
        response.setUsedProjects(List.of(projectId));
        return response;
    }

    /**
     * conversationId 处理：空白生成 UUID，非空透传。
     * Phase 9 语义：真正会话标识，配合 projectId 构成隔离键
     * assistant:{projectId}:{conversationId}（历史读写均经 ConversationHistoryStore）。
     */
    private String resolveConversationId(String conversationId) {
        return conversationId == null || conversationId.isBlank()
                ? UUID.randomUUID().toString()
                : conversationId;
    }

    /**
     * 结构化值行 → STRUCTURED 证据项。
     */
    private AssistantReferenceVO toStructuredReference(ProjectStructuredFactsVO.Field field,
                                                       ProjectStructuredFactsVO.ValueItem item) {
        AssistantReferenceVO ref = new AssistantReferenceVO();
        ref.setType(AssistantReferenceVO.TYPE_STRUCTURED);
        ref.setFieldCode(field.getFieldCode());
        ref.setFieldName(field.getFieldName());
        ref.setRawValue(item.getRawValue());
        ref.setNormalizedValue(item.getNormalizedValue());
        ref.setUnit(item.getUnit());
        ref.setSourceFileId(item.getSourceFileId());
        ref.setSourceFileName(item.getSourceFileName());
        ref.setSourcePage(item.getSourcePage());
        ref.setSourceChunkId(item.getSourceChunkId());
        return ref;
    }

    /**
     * RAG 切片 → FILE 证据项。
     */
    private AssistantReferenceVO toFileReference(ChunkMetadataReader.ChunkSource source, String chunkId) {
        AssistantReferenceVO ref = new AssistantReferenceVO();
        ref.setType(AssistantReferenceVO.TYPE_FILE);
        ref.setFileId(source.fileId());
        ref.setFileName(source.fileName());
        ref.setPage(source.page());
        ref.setChunkId(chunkId);
        return ref;
    }

    /**
     * 展平全部表单实例的字段事实（structuredData 供前端渲染冲突卡片）。
     */
    private List<ProjectStructuredFactsVO.Field> collectFields(ProjectStructuredFactsVO facts) {
        List<ProjectStructuredFactsVO.Field> fields = new ArrayList<>();
        if (facts != null) {
            facts.getForms().forEach(form -> fields.addAll(form.getFields()));
        }
        return fields;
    }
}
