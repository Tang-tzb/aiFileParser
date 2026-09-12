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
 * 项目助手服务实现（需求 §二十八编排式流程，Phase 8 版）
 * <p>
 * 执行序：参数防御 → ProjectService 守门（403/6001 唯一入口）→
 * QueryIntentAnalyzer（第一次 LLM，轻量：仅 projectName+question）→
 * UNSUPPORTED 短路（零 LLM 零检索，追加约束 5）→ QueryPlanner 按
 * AssistantQueryPlan 拉取 结构化事实/项目 RAG/文件清单 → AssistantPromptBuilder
 * （反伪造 System Prompt）→ ChatModel 最终回答（纯自然语言）→ 组装响应。
 * <p>
 * 硬边界：不解析业务数字（追加约束 10）；不读 ChatMemory（追加约束 8）；
 * 不直查 Mapper（Phase 7 约束 3）；引用为"可使用的证据集合"而非 LLM 实际引用
 * （追加约束 3，Phase 11 精修 citation）。
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
    private final ChatModel chatModel;

    /**
     * 助手 RAG 检索条数（独立于抽取链 rag.retrieve.topK）
     */
    @Value("${assistant.retrieve.topK:5}")
    private int assistantTopK;

    @Override
    public AssistantChatResponse chat(Long projectId, AssistantChatRequest request) {
        // 参数防御（@NotBlank 之外的兜底，直调场景可达）
        if (request.getMessage() == null || request.getMessage().isBlank()) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "提问内容不能为空");
        }
        String conversationId = resolveConversationId(request.getConversationId());
        // 权限/存在性守门唯一入口（403/6001）；projectName 仅消歧语境（追加约束 13）
        ProjectVO project = projectService.getProjectById(projectId);
        AssistantIntent intent = queryIntentAnalyzer.analyze(request.getMessage(), project.getProjectName());
        // UNSUPPORTED 真短路（追加约束 5/14）：意图识别后立即返回，禁止任何检索/事实查询
        if (intent == AssistantIntent.UNSUPPORTED) {
            return buildUnsupportedResponse(projectId, conversationId);
        }
        return answer(projectId, conversationId, request.getMessage(), project.getProjectName(), intent);
    }

    // ==================== 内部方法 ====================

    /**
     * 执行回答主链路：按计划拉数 → 组装 Context → 第二次 LLM → 响应组装。
     */
    private AssistantChatResponse answer(Long projectId, String conversationId, String message,
                                         String projectName, AssistantIntent intent) {
        AssistantQueryPlan plan = queryPlanner.buildPlan(intent);
        ProjectStructuredFactsVO facts = plan.isFetchFacts()
                ? projectQueryService.queryProjectFacts(projectId) : null;
        List<Document> documents = plan.isFetchRag()
                ? projectRetrievalService.retrieve(projectId, message, assistantTopK) : List.of();
        List<String> fileNames = plan.isFetchFiles() ? fetchFileNames(projectId) : List.of();
        ProjectAssistantContext ctx = ProjectAssistantContext.builder()
                .projectName(projectName)
                .question(message)
                .facts(facts)
                .documents(documents)
                .fileNames(fileNames)
                .factsUsed(facts != null)
                .ragUsed(plan.isFetchRag())
                .filesUsed(plan.isFetchFiles())
                .build();
        String answerText = generateAnswer(ctx);
        AssistantChatResponse response = assembleResponse(projectId, conversationId, answerText, ctx);
        log.info("项目助手问答完成 projectId={}, intent={}, facts={}, rag={}, files={}, references={}",
                projectId, intent, facts != null, plan.isFetchRag(), plan.isFetchFiles(),
                response.getReferences().size());
        return response;
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
     * UNSUPPORTED 确定性响应（追加约束 14 文案明确）：零 LLM 零检索，仅用于
     * 告知能力边界，不产生任何证据项。
     */
    private AssistantChatResponse buildUnsupportedResponse(Long projectId, String conversationId) {
        AssistantChatResponse response = new AssistantChatResponse();
        response.setConversationId(conversationId);
        response.setAnswer("当前版本暂不支持项目之间的比较、排序、统计、筛选和归因分析，请调整问题后重试。");
        response.setUsedProjects(List.of(projectId));
        return response;
    }

    /**
     * conversationId 处理：空白生成 UUID，非空透传（Phase 8 不读 ChatMemory）。
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
