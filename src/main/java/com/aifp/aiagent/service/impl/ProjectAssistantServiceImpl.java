package com.aifp.aiagent.service.impl;

import com.aifp.aiagent.assistant.*;
import com.aifp.aiagent.common.ResultCode;
import com.aifp.aiagent.dto.*;
import com.aifp.aiagent.exception.BusinessException;
import com.aifp.aiagent.rag.ProjectRetrievalService;
import com.aifp.aiagent.service.ProjectAssistantService;
import com.aifp.aiagent.service.ProjectQueryService;
import com.aifp.aiagent.service.ProjectService;
import jakarta.annotation.Resource;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.document.Document;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.Executor;
import java.util.function.Consumer;

/**
 * 项目助手服务实现（需求 §二十八编排式流程；Phase 9 增加会话隔离）
 * <p>
 * 执行序：参数防御 → ProjectService 守门（403/6001 唯一入口，权限前置——
 * 守门通过前零 Redis 访问）→ 会话历史读取（ConversationHistoryStore，降级安全）→
 * QueryIntentAnalyzer（第一次 LLM：意图 + 指代消解改写，仅注入历史用户问题）→
 * UNSUPPORTED 短路（追加约束 5，Phase 10 文案收窄为归因分析）→ QueryPlanner 按
 * AssistantQueryPlan 拉取结构化事实/项目 RAG（query=standaloneQuestion）/文件清单 →
 * AssistantPromptBuilder.build（反伪造 System Prompt + 对话历史段 + Phase 11 证据
 * 编号登记，返回 PromptBuildResult）→ ChatModel 流式最终回答（delta 增量推送）→
 * AnswerCitationParser 确定性解析行内引用标记（只降级不报错）→ 组装 Response →
 * final 事件收尾 → appendTurn（后置：写失败不影响已生成的回答）。
 * <p>
 * Phase K 流式输出：守门同步完成后建立 SSE 连接（timeout 120s），编排提交
 * assistantStreamExecutor 专用线程池异步执行；事件协议 delta/final/error，
 * 任意异常以 error 事件 + complete 兜底收敛。
 * <p>
 * Phase 10 COMPARISON 分支：比较编排（槽位 LLM + 名称→ID + 确定性计算）先行，
 * 降级零后续 LLM 零检索；成功后 RAG 仅解释依据，比较证据并入 references/usedFiles/
 * usedProjects，comparisonData 单独返回。
 * <p>
 * 硬边界：不解析业务数字（追加约束 10）；不直查 Mapper（Phase 7 约束 3）；
 * 历史不是事实来源——项目事实每轮必须重新从本次 ProjectQueryService/
 * ProjectRetrievalService 拉取（Phase 9 追加约束 1）。Phase 11 引用语义：
 * references = 本次注入 LLM 的全量证据集合（追加约束 3），citations = answer 中
 * 引用标记经确定性解析映射的实际引用子集（只能来自 evidence，未知标记 warn 忽略；
 * 解析异常降级为部分 citations，不影响已成功的 answer）。
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
    private final AnswerCitationParser citationParser;
    private final ConversationHistoryStore conversationHistoryStore;
    private final ProjectComparisonService comparisonService;
    private final ChatModel chatModel;

    /**
     * SSE 事件名（Phase K 流式协议）：delta=回答增量 / final=完整响应 / error=失败消息
     */
    private static final String EVENT_DELTA = "delta";
    private static final String EVENT_FINAL = "final";
    private static final String EVENT_ERROR = "error";
    /**
     * SSE 连接超时（毫秒）：LLM 流式 + 前置编排的最长生命周期
     */
    private static final long SSE_TIMEOUT_MILLIS = 120_000L;
    /**
     * 助手流式对话专用线程池（Phase K；@Resource 字段注入以指定 bean 名，
     * 与 parseExecutor 隔离——LLM 流式任务 10~30s，防止双方互占）
     */
    @Resource(name = "assistantStreamExecutor")
    private Executor assistantStreamExecutor;

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
    public SseEmitter chatStream(Long projectId, AssistantChatRequest request) {
        // 参数防御（@NotBlank 之外的兜底，直调场景可达）——同步执行，失败走 HTTP JSON
        if (request.getMessage() == null || request.getMessage().isBlank()) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "提问内容不能为空");
        }
        String conversationId = resolveConversationId(request.getConversationId());
        // 权限/存在性守门唯一入口（403/6001）——同步执行，守门通过前零 Redis 访问（追加约束 6）
        ProjectVO project = projectService.getProjectById(projectId);

        // 守门通过后建立 SSE 连接（timeout 120s），编排提交专用线程池异步执行
        SseEmitter emitter = new SseEmitter(SSE_TIMEOUT_MILLIS);
        emitter.onTimeout(emitter::complete);
        emitter.onError(t -> log.debug("助手 SSE 连接异常 projectId={}: {}", projectId, t.getMessage()));
        assistantStreamExecutor.execute(() ->
                executeChatStream(emitter, projectId, conversationId,
                        request.getMessage(), project.getProjectName()));
        return emitter;
    }

    // ==================== 内部方法 ====================

    /**
     * SSE 流式编排（Phase K，异步线程执行）：
     * 历史读取 → 意图分析 → UNSUPPORTED 短路 / 比较分支 / 常规流式回答 →
     * final/error 事件收尾。任意异常都以 error 事件 + complete 兜底，保证前端
     * 占位消息总能收敛（不会永久"思考中"）。
     */
    protected void executeChatStream(SseEmitter emitter, Long projectId, String conversationId,
                                     String message, String projectName) {
        try {
            // 会话历史（Redis 异常降级无历史）：时间顺序，最近一轮在最后（追加约束 3）
            List<ConversationTurn> history = conversationHistoryStore
                    .loadRecentTurns(projectId, conversationId, maxInjectedTurns);
            List<String> recentQuestions = history.stream()
                    .map(ConversationTurn::getUserQuestion).toList();
            AssistantIntentAnalysis analysis = queryIntentAnalyzer
                    .analyze(message, projectName, recentQuestions);
            // delta 增量推送回调（常规路径与比较分支共用）
            Consumer<String> onDelta = text -> sendEvent(emitter, EVENT_DELTA, Map.of("delta", text));

            // UNSUPPORTED 真短路（追加约束 5/14）：意图识别后立即返回，禁止任何检索/事实查询
            if (analysis.getIntent() == AssistantIntent.UNSUPPORTED) {
                AssistantChatResponse response = buildUnsupportedResponse(projectId, conversationId);
                // 追加约束 5：只记录真实交互（assistantAnswer=实际返回的固定文案）
                conversationHistoryStore.appendTurn(projectId, conversationId,
                        message, response.getAnswer());
                sendEvent(emitter, EVENT_FINAL, response);
                finish(emitter);
                return;
            }

            AssistantChatResponse response = answer(projectId, conversationId, message,
                    projectName, analysis, history, onDelta);
            sendEvent(emitter, EVENT_FINAL, response);
            finish(emitter);
        } catch (BusinessException e) {
            sendErrorAndFinish(emitter, e.getMessage());
        } catch (Exception e) {
            log.error("项目助手流式问答失败 projectId={}: {}", projectId, e.getMessage(), e);
            sendErrorAndFinish(emitter, "回答生成失败，请稍后重试");
        }
    }

    /**
     * 执行回答主链路：按计划拉数 → 组装 Context → 第二次 LLM（流式，onDelta 增量推送）→
     * 响应组装 → 会话记录（后置）。
     */
    private AssistantChatResponse answer(Long projectId, String conversationId, String message,
                                         String projectName, AssistantIntentAnalysis analysis,
                                         List<ConversationTurn> history, Consumer<String> onDelta) {
        // 追加约束 2：standaloneQuestion 是本轮唯一有效问题，RAG query 与 Prompt 用户问题共用
        String effectiveQuestion = analysis.getStandaloneQuestion();
        AssistantQueryPlan plan = queryPlanner.buildPlan(analysis.getIntent());
        if (analysis.getIntent() == AssistantIntent.COMPARISON) {
            return answerComparison(projectId, conversationId, message, projectName,
                    effectiveQuestion, history, onDelta);
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
        // Phase 11：Prompt 渲染与证据登记一体完成（userPrompt 标记与 evidence 一一对应）
        AssistantPromptBuilder.PromptBuildResult built = promptBuilder.build(ctx);
        String answerText = generateAnswer(built.userPrompt(), onDelta);
        AssistantChatResponse response = assembleResponse(projectId, conversationId,
                answerText, ctx, built.evidence());
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
                                                   List<ConversationTurn> history, Consumer<String> onDelta) {
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
        // Phase 11：Prompt 渲染与证据登记一体完成（比较证据前置 + D 编号独立）
        AssistantPromptBuilder.PromptBuildResult built = promptBuilder.build(ctx);
        String answerText = generateAnswer(built.userPrompt(), onDelta);
        AssistantChatResponse response = assembleResponse(projectId, conversationId,
                answerText, ctx, built.evidence());
        response.setComparisonData(outcome.comparison());
        applyComparisonProjects(response, outcome.comparison());
        conversationHistoryStore.appendTurn(projectId, conversationId, message, answerText);
        log.info("跨项目比较完成 projectId={}, targets={}, units={}, excluded={}, references={}",
                projectId, outcome.comparison().getTargetProjectIds().size(),
                outcome.comparison().getUnits().size(),
                outcome.comparison().getExcluded().size(), response.getReferences().size());
        return response;
    }

    /**
     * 比较分支 usedProjects = 当前项目 ∪ 目标项目（TreeSet 排序，Phase 10 语义不变；
     * Phase 11 起 references/usedFiles/citations 统一由 assembleResponse 从
     * evidence 组装，此处仅补齐 usedProjects）。
     */
    private void applyComparisonProjects(AssistantChatResponse response,
                                         CrossProjectComparisonVO comparison) {
        Set<Long> projectIds = new TreeSet<>();
        projectIds.add(comparison.getCurrentProjectId());
        projectIds.addAll(comparison.getTargetProjectIds());
        response.setUsedProjects(new ArrayList<>(projectIds));
    }

    /**
     * 第二次 LLM 调用（最终回答，Phase K 流式版）。错误边界（追加约束 11）：调用异常
     * 或空白回答一律 3001 上抛，禁止降级 UNKNOWN 或重进 RAG 形成不可控循环；
     * Phase 11：引用解析不在本边界内——解析异常只降级 citations（约束 3）。
     * <p>
     * Phase K：chatModel.stream 逐块消费回答增量，每块经 onDelta 推送 SSE delta 事件；
     * 增量聚合为完整回答后统一走引用解析与响应组装（final 事件携带完整响应）。
     */
    private String generateAnswer(String userPrompt, Consumer<String> onDelta) {
        try {
            Prompt prompt = new Prompt(List.of(
                    new SystemMessage(promptBuilder.buildSystemPrompt()),
                    new UserMessage(userPrompt)));
            StringBuilder answer = new StringBuilder();
            chatModel.stream(prompt)
                    .doOnNext(response -> {
                        String delta = response.getResult().getOutput().getText();
                        if (delta != null && !delta.isEmpty()) {
                            answer.append(delta);
                            onDelta.accept(delta);
                        }
                    })
                    .blockLast();
            String text = answer.toString();
            if (text.isBlank()) {
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

    // ==================== SSE 收发辅助 ====================

    /**
     * 发送单个 SSE 事件（Phase K）。payload 经 Spring MVC 配置的 Jackson 转换器
     * 序列化（沿用 Long → String 防精度丢失全局契约）；连接断开（IOException）
     * 转为非受检异常由编排层兜底 complete。protected 供测试拦截记录事件
     * （ResponseBodyEmitter.Handler 为包私有 SPI，不可跨包实现）。
     */
    protected void sendEvent(SseEmitter emitter, String eventName, Object payload) {
        try {
            emitter.send(SseEmitter.event()
                    .name(eventName)
                    .data(payload, MediaType.APPLICATION_JSON));
        } catch (IOException e) {
            log.debug("助手 SSE 事件发送失败（客户端可能已断开）event={}: {}", eventName, e.getMessage());
            throw new IllegalStateException("SSE 事件发送失败", e);
        }
    }

    /**
     * 发送 error 事件并收尾：发送失败仅降级 debug（连接已断开时 error 也不可达），
     * 始终 complete 保证连接生命周期收敛。
     */
    private void sendErrorAndFinish(SseEmitter emitter, String message) {
        try {
            sendEvent(emitter, EVENT_ERROR, Map.of("message", message));
        } catch (Exception e) {
            log.debug("助手 SSE error 事件发送失败: {}", e.getMessage());
        } finally {
            finish(emitter);
        }
    }

    /**
     * 收尾 complete（幂等安全：连接已断开时 complete 异常仅降级 debug）
     */
    private void finish(SseEmitter emitter) {
        try {
            emitter.complete();
        } catch (Exception e) {
            log.debug("助手 SSE complete 失败: {}", e.getMessage());
        }
    }

    /**
     * 组装响应（Phase 11）：
     * references = PromptBuildResult.evidence（本次注入 LLM 的全量证据，顺序即
     * Prompt 渲染顺序，追加约束 7 兼容既有前端语义）；
     * usedFiles = 从 evidence 顺序派生（约束 8：与 LLM 是否实际引用无关）；
     * citations = AnswerCitationParser 从 answer 确定性解析的实际引用子集
     * （只能来自 evidence，未知标记 warn 忽略，解析异常降级不报错，约束 2/3）；
     * structuredData = 本次注入 LLM 的字段事实；全部后端组装（追加约束 12）。
     */
    private AssistantChatResponse assembleResponse(Long projectId, String conversationId,
                                                   String answerText, ProjectAssistantContext ctx,
                                                   List<AssistantReferenceVO> evidence) {
        AssistantChatResponse response = new AssistantChatResponse();
        response.setConversationId(conversationId);
        response.setAnswer(answerText);
        response.setReferences(new ArrayList<>(evidence));
        response.setUsedFiles(deriveUsedFiles(evidence));
        response.setCitations(citationParser.parse(answerText, evidence));
        response.setUsedProjects(List.of(projectId));
        response.setStructuredData(ctx.isFactsUsed() ? collectFields(ctx.getFacts()) : new ArrayList<>());
        return response;
    }

    /**
     * usedFiles 从 evidence 顺序派生（约束 8）：结构化来源文件（sourceFileId）∪
     * RAG 命中文件（fileId），LinkedHashSet 按 evidence 顺序保序去重——比较分支
     * "比较来源前置"语义随 evidence 顺序自动保持；文件清单场景不贡献证据，
     * 与既有语义一致。
     */
    private List<Long> deriveUsedFiles(List<AssistantReferenceVO> evidence) {
        Set<Long> usedFileIds = new LinkedHashSet<>();
        for (AssistantReferenceVO ref : evidence) {
            Long fileId = AssistantReferenceVO.TYPE_STRUCTURED.equals(ref.getType())
                    ? ref.getSourceFileId() : ref.getFileId();
            if (fileId != null) {
                usedFileIds.add(fileId);
            }
        }
        return new ArrayList<>(usedFileIds);
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
