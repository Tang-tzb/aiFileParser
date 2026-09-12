package com.aifp.aiagent.service.impl;

import com.aifp.aiagent.assistant.*;
import com.aifp.aiagent.common.ResultCode;
import com.aifp.aiagent.dto.*;
import com.aifp.aiagent.entity.enums.FieldType;
import com.aifp.aiagent.exception.BusinessException;
import com.aifp.aiagent.rag.ProjectRetrievalService;
import com.aifp.aiagent.service.ProjectQueryService;
import com.aifp.aiagent.service.ProjectService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.document.Document;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.anyInt;
import static org.mockito.Mockito.*;

/**
 * {@link ProjectAssistantServiceImpl} 测试（离线，Phase 8 T4 / Phase 9 会话编排 /
 * Phase K 流式输出适配）
 * <p>
 * Phase 8 覆盖：参数防御 400；守门 403/6001 透传（同步抛出，SSE 连接建立前）；
 * UNSUPPORTED 真短路（追加约束 5，verify 零 LLM/零检索/零事实查询/零文件清单）；
 * 五意图数据源路由（STRUCTURED 恒 facts+RAG 追加约束 2）；conversationId 空生成/
 * 非空透传；回答 LLM 异常/空白 → error 事件收尾不重进 RAG（追加约束 11）；
 * references/structuredData/usedFiles 组装（追加约束 3/12，后端组装非 LLM 生成）。
 * <p>
 * Phase 9 追加覆盖：历史按 max-injected-turns 读取并按时间序传入意图识别（约束 3）；
 * standaloneQuestion 同时用于 RAG query 与回答 Prompt、原始 message 忠实入历史（约束 2）；
 * UNSUPPORTED 记录实际返回固定文案、失败零轮次记录（约束 5）；成功轮次后置
 * appendTurn（约束 4）。
 * <p>
 * Phase 11 追加覆盖：answer 行内引用标记 → citations 实际引用子集（首次出现顺序
 * 去重、与 references 共享实例）；未知标记仅忽略不影响主回答（约束 2/3）；
 * answer 保留标记不剥离（约束 12）。
 * <p>
 * Phase K 流式覆盖：SSE 事件协议 delta（{"delta":"..."}）→ final（完整响应）/
 * error（{"message":"..."}）；流式 LLM 每块增量各推送一个 delta 事件、聚合文本
 * 作为 final 响应；守门失败同步抛出（HTTP JSON），编排失败 error 事件 + complete
 * 兜底收敛。测试通过「捕获线程池提交的编排任务 + 拦截 sendEvent 测试缝」模式
 * 离线断言事件序列（ResponseBodyEmitter.Handler 为包私有 SPI 不可跨包实现，
 * 真实 SseEmitter 不发送任何数据，不启 Spring 上下文）。
 * QueryPlanner/PromptBuilder/AnswerCitationParser/ChunkMetadataReader 为纯函数/
 * 渲染组件使用真实实例。
 *
 * @author Tang_tzb
 */
@ExtendWith(MockitoExtension.class)
class ProjectAssistantServiceImplTest {

    private static final Long PROJECT_ID = 1785900001L;
    private static final Long PROJECT_FORM_ID = 1785600001L;
    private static final Long FILE_A = 1785800001L;
    private static final Long FILE_B = 1785800002L;
    private static final Long TARGET_PROJECT_ID = 1785900002L;
    private static final Long TARGET_FORM_ID = 1785600002L;
    private static final String MESSAGE = "这个项目总投资是多少？";

    @Mock
    private ProjectService projectService;
    @Mock
    private ProjectQueryService projectQueryService;
    @Mock
    private ProjectRetrievalService projectRetrievalService;
    @Mock
    private QueryIntentAnalyzer queryIntentAnalyzer;
    @Mock
    private ConversationHistoryStore conversationHistoryStore;
    @Mock
    private ProjectComparisonService comparisonService;
    @Mock
    private ChatModel chatModel;

    private ProjectAssistantServiceImpl service;

    /**
     * sendEvent 拦截记录（Phase K 测试缝）：记录 (eventName, payload) 事件序列
     */
    private final List<SentEvent> recordedEvents = new ArrayList<>();

    @BeforeEach
    void setUp() {
        service = new ProjectAssistantServiceImpl(
                projectService, projectQueryService, projectRetrievalService,
                queryIntentAnalyzer, new QueryPlanner(),
                new AssistantPromptBuilder(new ChunkMetadataReader()),
                new AnswerCitationParser(), conversationHistoryStore,
                comparisonService, chatModel) {
            @Override
            protected void sendEvent(SseEmitter emitter, String eventName, Object payload) {
                recordedEvents.add(new SentEvent(eventName, payload));
            }
        };
        ReflectionTestUtils.setField(service, "assistantTopK", 5);
        ReflectionTestUtils.setField(service, "maxInjectedTurns", 6);
        // 默认直跑执行器：守门失败类用例在 chatStream 同步段抛出，不会触达线程池
        ReflectionTestUtils.setField(service, "assistantStreamExecutor", (Executor) Runnable::run);
    }

    // ==================== 参数防御与守门（同步段，HTTP JSON） ====================

    /**
     * message 空白 → 400，快速失败不触碰任何依赖（SSE 连接未建立，走 HTTP JSON）
     */
    @Test
    void chatStream_blankMessage_throwsParamError400() {
        assertThatThrownBy(() -> service.chatStream(PROJECT_ID, req("   ")))
                .isInstanceOfSatisfying(BusinessException.class, e ->
                        assertThat(e.getCode()).isEqualTo(ResultCode.PARAM_ERROR.getCode()));
        verifyNoInteractions(projectService, projectQueryService,
                projectRetrievalService, queryIntentAnalyzer, conversationHistoryStore, chatModel);
    }

    /**
     * 守门唯一入口 ProjectService（403）：意图识别前拦截
     */
    @Test
    void chatStream_accessDenied_propagates403() {
        when(projectService.getProjectById(PROJECT_ID)).thenThrow(new BusinessException(ResultCode.FORBIDDEN));

        assertThatThrownBy(() -> service.chatStream(PROJECT_ID, req(MESSAGE)))
                .isInstanceOfSatisfying(BusinessException.class, e ->
                        assertThat(e.getCode()).isEqualTo(ResultCode.FORBIDDEN.getCode()));
        verifyNoInteractions(queryIntentAnalyzer, projectQueryService,
                projectRetrievalService, conversationHistoryStore, chatModel);
    }

    /**
     * 守门唯一入口 ProjectService（6001）
     */
    @Test
    void chatStream_projectMissing_propagates6001() {
        when(projectService.getProjectById(PROJECT_ID)).thenThrow(
                new BusinessException(ResultCode.PROJECT_NOT_FOUND));

        assertThatThrownBy(() -> service.chatStream(PROJECT_ID, req(MESSAGE)))
                .isInstanceOfSatisfying(BusinessException.class, e ->
                        assertThat(e.getCode()).isEqualTo(ResultCode.PROJECT_NOT_FOUND.getCode()));
        // 守门前零 Redis 访问（追加约束 6）
        verifyNoInteractions(queryIntentAnalyzer, projectQueryService,
                projectRetrievalService, conversationHistoryStore, chatModel);
    }

    // ==================== SSE 流式编排（异步段，事件协议） ====================

    /**
     * 意图识别 ChatModel 异常（3001）→ error 事件收尾，不进入任何检索；
     * 追加约束 5：模型失败零轮次记录
     */
    @Test
    @SuppressWarnings("unchecked")
    void chatStream_intentAnalyzerFailure_errorEventAndNoRag() {
        when(projectService.getProjectById(PROJECT_ID)).thenReturn(projectVO());
        when(conversationHistoryStore.loadRecentTurns(eq(PROJECT_ID), anyString(), eq(6)))
                .thenReturn(List.of());
        when(queryIntentAnalyzer.analyze(eq(MESSAGE), eq("示范项目"), anyList())).thenThrow(
                new BusinessException(ResultCode.AI_INVOKE_ERROR, "AI 意图识别调用失败"));

        List<SentEvent> events = runChat(req(MESSAGE));

        assertThat(events).hasSize(1);
        assertThat(events.get(0).name()).isEqualTo("error");
        assertThat(((Map<String, String>) events.get(0).payload()).get("message"))
                .isEqualTo("AI 意图识别调用失败");
        verify(conversationHistoryStore, never())
                .appendTurn(any(), anyString(), anyString(), anyString());
        verifyNoInteractions(projectQueryService, projectRetrievalService, chatModel);
    }

    // ==================== UNSUPPORTED 真短路（追加约束 5/14） ====================

    /**
     * UNSUPPORTED → 意图识别后立即 final 事件返回确定性文案，零 LLM 零检索零事实查询
     * 零文件清单；Phase 10 文案收窄（追加约束 13）：仅归因分析不支持；追加约束 5：
     * 记录真实交互（assistantAnswer = 实际返回给用户的固定文案）
     */
    @Test
    void chatStream_unsupported_shortCircuitWithDeterministicAnswer() {
        when(projectService.getProjectById(PROJECT_ID)).thenReturn(projectVO());
        when(conversationHistoryStore.loadRecentTurns(eq(PROJECT_ID), anyString(), eq(6)))
                .thenReturn(List.of());
        when(queryIntentAnalyzer.analyze(eq(MESSAGE), eq("示范项目"), anyList()))
                .thenReturn(analysis(AssistantIntent.UNSUPPORTED));

        AssistantChatResponse resp = finalResponseOf(runChat(req(MESSAGE)));

        assertThat(resp.getAnswer())
                .contains("当前版本暂不支持项目之间的归因分析与原因解释");
        assertThat(resp.getReferences()).isEmpty();
        assertThat(resp.getCitations()).isEmpty();
        assertThat(resp.getStructuredData()).isEmpty();
        assertThat(resp.getUsedProjects()).containsExactly(PROJECT_ID);
        assertThat(resp.getUsedFiles()).isEmpty();
        verifyNoInteractions(projectQueryService, projectRetrievalService, chatModel);
        verify(projectService, never()).listProjectFiles(any(), any());
        verify(conversationHistoryStore).appendTurn(eq(PROJECT_ID), anyString(), eq(MESSAGE),
                eq("当前版本暂不支持项目之间的归因分析与原因解释，请调整问题后重试。"));
    }

    // ==================== 意图路由（§十七 + 追加约束 2） ====================

    /**
     * STRUCTURED → 事实 + RAG 恒兜底（不判 forms 空），不拉文件清单；
     * structuredData/references/usedFiles 全部后端组装
     */
    @Test
    void chatStream_structured_factsAndRagAlwaysFetched() {
        stubCommon(AssistantIntent.STRUCTURED);
        stubRag(List.of());
        stubAnswer("总投资约100万元。");
        when(projectQueryService.queryProjectFacts(PROJECT_ID)).thenReturn(facts());

        AssistantChatResponse resp = finalResponseOf(runChat(req(MESSAGE)));

        verify(projectRetrievalService).retrieve(PROJECT_ID, MESSAGE, 5);
        verify(projectService, never()).listProjectFiles(any(), any());
        assertThat(resp.getAnswer()).isEqualTo("总投资约100万元。");
        // structuredData = 本次注入的全部字段事实
        assertThat(resp.getStructuredData()).hasSize(1);
        assertThat(resp.getStructuredData().get(0).getProjectFormId()).isEqualTo(PROJECT_FORM_ID);
        // references = 全部结构化来源（2 条 ValueItem 独立成项，冲突语义保留）
        assertThat(resp.getReferences()).hasSize(2);
        AssistantReferenceVO first = resp.getReferences().get(0);
        assertThat(first.getType()).isEqualTo(AssistantReferenceVO.TYPE_STRUCTURED);
        assertThat(first.getFieldCode()).isEqualTo("total_investment");
        assertThat(first.getRawValue()).isEqualTo("100万");
        assertThat(first.getNormalizedValue()).isEqualTo("1000000");
        assertThat(first.getSourceFileId()).isEqualTo(FILE_A);
        // usedFiles = 结构化来源 ∪ RAG 命中去重
        assertThat(resp.getUsedFiles()).containsExactly(FILE_A, FILE_B);
        // 追加约束 4：成功轮次后置记录，userQuestion = 原始 message（忠实记录，非改写值）
        verify(conversationHistoryStore).appendTurn(
                PROJECT_ID, resp.getConversationId(), MESSAGE, "总投资约100万元。");
    }

    /**
     * DOCUMENT → 仅 RAG：事实零查询、structuredData 为空，FILE 组证据来自 metadata
     * （String 化 fileId/fileName/pageStart 由 ChunkMetadataReader 解析）
     */
    @Test
    void chatStream_document_ragOnlyWithFileReferences() {
        stubCommon(AssistantIntent.DOCUMENT);
        stubRag(List.of(ragDoc()));
        stubAnswer("总投资约100万元。");

        AssistantChatResponse resp = finalResponseOf(runChat(req(MESSAGE)));

        verifyNoInteractions(projectQueryService);
        verify(projectService, never()).listProjectFiles(any(), any());
        assertThat(resp.getStructuredData()).isEmpty();
        assertThat(resp.getReferences()).hasSize(1);
        AssistantReferenceVO ref = resp.getReferences().get(0);
        assertThat(ref.getType()).isEqualTo(AssistantReferenceVO.TYPE_FILE);
        assertThat(ref.getFileId()).isEqualTo(FILE_A);
        assertThat(ref.getFileName()).isEqualTo("预算说明书.pdf");
        assertThat(ref.getPage()).isEqualTo(3);
        assertThat(resp.getUsedFiles()).containsExactly(FILE_A);
    }

    /**
     * FILE_LIST → 仅文件清单；清单文件不贡献 references/usedFiles
     * （引用证据仅两类：结构化来源 ∪ RAG 命中）
     */
    @Test
    @SuppressWarnings("unchecked")
    void chatStream_fileList_fileNamesOnly() {
        stubCommon(AssistantIntent.FILE_LIST);
        stubAnswer("总投资约100万元。");
        when(projectService.listProjectFiles(eq(PROJECT_ID), any(PageQuery.class)))
                .thenReturn(filesPage());

        AssistantChatResponse resp = finalResponseOf(runChat(req(MESSAGE)));

        ArgumentCaptor<PageQuery> captor = ArgumentCaptor.forClass(PageQuery.class);
        verify(projectService).listProjectFiles(eq(PROJECT_ID), captor.capture());
        // 追加约束 7：PageQuery(1,100) 当前版本限制
        assertThat(captor.getValue().getPageNum()).isEqualTo(1);
        assertThat(captor.getValue().getPageSize()).isEqualTo(100);
        verifyNoInteractions(projectQueryService, projectRetrievalService);
        assertThat(resp.getReferences()).isEmpty();
        assertThat(resp.getCitations()).isEmpty();
        assertThat(resp.getUsedFiles()).isEmpty();
    }

    /**
     * UNKNOWN → 保守降级事实 + RAG 双通道（§十六）
     */
    @Test
    void chatStream_unknown_factsAndRag() {
        stubCommon(AssistantIntent.UNKNOWN);
        stubRag(List.of());
        stubAnswer("总投资约100万元。");
        when(projectQueryService.queryProjectFacts(PROJECT_ID)).thenReturn(facts());

        runChat(req(MESSAGE));

        verify(projectQueryService).queryProjectFacts(PROJECT_ID);
        verify(projectRetrievalService).retrieve(PROJECT_ID, MESSAGE, 5);
    }

    /**
     * HYBRID → 事实 + RAG
     */
    @Test
    void chatStream_hybrid_factsAndRag() {
        stubCommon(AssistantIntent.HYBRID);
        stubRag(List.of());
        stubAnswer("总投资约100万元。");
        when(projectQueryService.queryProjectFacts(PROJECT_ID)).thenReturn(facts());

        runChat(req(MESSAGE));

        verify(projectQueryService).queryProjectFacts(PROJECT_ID);
        verify(projectRetrievalService).retrieve(PROJECT_ID, MESSAGE, 5);
    }

    // ==================== Phase 10 COMPARISON 分支 ====================

    /**
     * COMPARISON 成功路径：比较编排先行（不拉单项目全量事实）→ RAG 仅解释依据 →
     * 最终 LLM 流式回答（delta 增量）；comparisonData 返回、比较证据并入
     * references/usedFiles、usedProjects = 当前 ∪ 目标（追加约束 6/9/11）
     */
    @Test
    void chatStream_comparison_success_returnsComparisonDataWithEvidence() {
        stubCommon(AssistantIntent.COMPARISON);
        CrossProjectComparisonVO comparison = comparisonVO();
        when(comparisonService.compare(PROJECT_ID, MESSAGE))
                .thenReturn(ProjectComparisonService.ComparisonOutcome.of(comparison));
        stubRag(List.of(ragDoc()));
        stubAnswer("项目B投资最高。");

        AssistantChatResponse resp = finalResponseOf(runChat(req(MESSAGE)));

        // 比较编排先行；单项目全量事实零查询（字段级事实由比较服务内部拉取）
        verify(comparisonService).compare(PROJECT_ID, MESSAGE);
        verifyNoInteractions(projectQueryService);
        // RAG 以 standaloneQuestion 执行，仅解释依据
        verify(projectRetrievalService).retrieve(PROJECT_ID, MESSAGE, 5);
        assertThat(resp.getComparisonData()).isSameAs(comparison);
        // references = 参与单元 + 排除单元来源值（STRUCTURED）+ RAG 命中（FILE）
        assertThat(resp.getReferences()).hasSize(3);
        assertThat(resp.getReferences()).extracting(AssistantReferenceVO::getType)
                .containsExactly("STRUCTURED", "STRUCTURED", "FILE");
        assertThat(resp.getReferences().get(0).getFieldCode()).isEqualTo("total_investment");
        assertThat(resp.getReferences().get(0).getRawValue()).isEqualTo("200万");
        assertThat(resp.getReferences().get(1).getRawValue()).isEqualTo("300万");
        // usedFiles = 比较来源（参与 FILE_B + 排除 FILE_A）∪ RAG 命中 FILE_A 去重
        assertThat(resp.getUsedFiles()).containsExactly(FILE_B, FILE_A);
        // usedProjects = 当前项目 ∪ 目标项目升序
        assertThat(resp.getUsedProjects()).containsExactly(PROJECT_ID, TARGET_PROJECT_ID);
        // 回答经最终 LLM 生成并后置入历史
        assertThat(resp.getAnswer()).isEqualTo("项目B投资最高。");
        verify(conversationHistoryStore).appendTurn(
                PROJECT_ID, resp.getConversationId(), MESSAGE, "项目B投资最高。");
    }

    /**
     * COMPARISON 降级路径（追加约束 2/12）：确定性文案直接 final 事件返回——零后续
     * RAG、零最终 LLM、零事实查询；comparisonData=null；确定性答案忠实入历史
     */
    @Test
    void chatStream_comparison_fallback_deterministicAnswerWithoutRagOrLlm() {
        stubCommon(AssistantIntent.COMPARISON);
        String fallback = "无法确定比较范围：问题未明确参与比较的项目，请补充项目名称。";
        when(comparisonService.compare(PROJECT_ID, MESSAGE))
                .thenReturn(ProjectComparisonService.ComparisonOutcome.fallback(fallback));

        AssistantChatResponse resp = finalResponseOf(runChat(req(MESSAGE)));

        assertThat(resp.getAnswer()).isEqualTo(fallback);
        assertThat(resp.getComparisonData()).isNull();
        assertThat(resp.getReferences()).isEmpty();
        assertThat(resp.getCitations()).isEmpty();
        assertThat(resp.getStructuredData()).isEmpty();
        assertThat(resp.getUsedProjects()).containsExactly(PROJECT_ID);
        verifyNoInteractions(projectRetrievalService, projectQueryService, chatModel);
        verify(conversationHistoryStore).appendTurn(
                eq(PROJECT_ID), anyString(), eq(MESSAGE), eq(fallback));
    }

    // ==================== Phase 11 引用溯源（citations） ====================

    /**
     * answer 含行内引用标记 → citations = 实际引用子集：按首次出现顺序去重、
     * 与 references 共享同一 VO 实例；answer 保留标记不剥离（约束 12）
     */
    @Test
    void chatStream_citationsParsedFromAnswerMarkers() {
        stubCommon(AssistantIntent.STRUCTURED);
        stubRag(List.of());
        stubAnswer("总投资约100万元[S1]，另一记载为200万元[S2]。[S1]再次出现。");
        when(projectQueryService.queryProjectFacts(PROJECT_ID)).thenReturn(facts());

        AssistantChatResponse resp = finalResponseOf(runChat(req(MESSAGE)));

        // references = 全量证据（facts 两条来源值 S1/S2）
        assertThat(resp.getReferences()).extracting(AssistantReferenceVO::getCitationId)
                .containsExactly("S1", "S2");
        // citations = 实际引用子集，首次出现顺序去重（重复 [S1] 只计一次）
        assertThat(resp.getCitations()).hasSize(2);
        assertThat(resp.getCitations().get(0)).isSameAs(resp.getReferences().get(0));
        assertThat(resp.getCitations().get(1)).isSameAs(resp.getReferences().get(1));
        // answer 保留标记（约束 12：后端不剥离，前端负责渲染）
        assertThat(resp.getAnswer()).contains("[S1]").contains("[S2]");
    }

    /**
     * LLM 编造的标记（[S99]/[D9]，本轮 evidence 不存在）→ 仅 warn 忽略：
     * 不创建新证据项、不改变 references，主回答原样返回不受影响（约束 2/3）
     */
    @Test
    void chatStream_unknownCitationMarkersIgnored_answerUnaffected() {
        stubCommon(AssistantIntent.STRUCTURED);
        stubRag(List.of());
        stubAnswer("总投资约100万元[S1]；编造[S99]与历史[D9]。");
        when(projectQueryService.queryProjectFacts(PROJECT_ID)).thenReturn(facts());

        AssistantChatResponse resp = finalResponseOf(runChat(req(MESSAGE)));

        assertThat(resp.getReferences()).hasSize(2);
        assertThat(resp.getCitations()).hasSize(1);
        assertThat(resp.getCitations().get(0).getCitationId()).isEqualTo("S1");
        assertThat(resp.getAnswer()).isEqualTo("总投资约100万元[S1]；编造[S99]与历史[D9]。");
    }

    // ==================== 回答 LLM 错误边界（追加约束 11） ====================

    /**
     * 回答 ChatModel 流式调用异常 → error 事件（3001 文案），禁止降级 UNKNOWN 或重进 RAG
     */
    @Test
    void chatStream_answerModelFailure_errorEventNoRetry() {
        stubCommon(AssistantIntent.STRUCTURED);
        stubRag(List.of());
        when(projectQueryService.queryProjectFacts(PROJECT_ID)).thenReturn(facts());
        // 回答桩覆盖验证：异常路径不依赖 stubAnswer
        when(chatModel.stream(any(Prompt.class))).thenThrow(new RuntimeException("timeout"));

        List<SentEvent> events = runChat(req(MESSAGE));

        SentEvent last = events.get(events.size() - 1);
        assertThat(last.name()).isEqualTo("error");
        assertThat(((Map<String, String>) last.payload()).get("message")).isEqualTo("AI 回答调用失败");
        // 不重试：事实与检索各只查询一次
        verify(projectQueryService, times(1)).queryProjectFacts(PROJECT_ID);
        verify(projectRetrievalService, times(1)).retrieve(any(Long.class), any(), anyInt());
        // 追加约束 5：3001 模型失败不得记录不完整轮次
        verify(conversationHistoryStore, never())
                .appendTurn(any(), anyString(), anyString(), anyString());
    }

    /**
     * 回答空白 → error 事件（"AI 未返回有效回答"），不重进 RAG
     */
    @Test
    @SuppressWarnings("unchecked")
    void chatStream_blankAnswer_errorEvent() {
        stubCommon(AssistantIntent.STRUCTURED);
        stubRag(List.of());
        when(projectQueryService.queryProjectFacts(PROJECT_ID)).thenReturn(facts());
        when(chatModel.stream(any(Prompt.class))).thenReturn(Flux.just(chatResponse("   ")));

        List<SentEvent> events = runChat(req(MESSAGE));

        SentEvent last = events.get(events.size() - 1);
        assertThat(last.name()).isEqualTo("error");
        assertThat(((Map<String, String>) last.payload()).get("message")).isEqualTo("AI 未返回有效回答");
        verify(conversationHistoryStore, never())
                .appendTurn(any(), anyString(), anyString(), anyString());
    }

    // ==================== Phase K 流式增量（delta 协议） ====================

    /**
     * 流式 LLM 每块增量各推送一个 delta 事件（顺序保序），聚合完整回答作为 final
     * 响应返回；delta 先于 final（前端占位消息按序渲染）
     */
    @Test
    @SuppressWarnings("unchecked")
    void chatStream_streamedChunks_pushDeltaEventsBeforeFinal() {
        stubCommon(AssistantIntent.STRUCTURED);
        stubRag(List.of());
        when(projectQueryService.queryProjectFacts(PROJECT_ID)).thenReturn(facts());
        when(chatModel.stream(any(Prompt.class))).thenReturn(Flux.just(
                chatResponse("总投资约"), chatResponse("100万元。")));

        List<SentEvent> events = runChat(req(MESSAGE));

        assertThat(events).hasSize(3);
        assertThat(events.get(0).name()).isEqualTo("delta");
        assertThat(((Map<String, String>) events.get(0).payload()).get("delta")).isEqualTo("总投资约");
        assertThat(events.get(1).name()).isEqualTo("delta");
        assertThat(((Map<String, String>) events.get(1).payload()).get("delta")).isEqualTo("100万元。");
        assertThat(events.get(2).name()).isEqualTo("final");
        assertThat(((AssistantChatResponse) events.get(2).payload()).getAnswer())
                .isEqualTo("总投资约100万元。");
        // 完整回答后置入历史
        verify(conversationHistoryStore).appendTurn(
                eq(PROJECT_ID), anyString(), eq(MESSAGE), eq("总投资约100万元。"));
    }

    // ==================== 会话无状态（追加约束 8） ====================

    /**
     * conversationId 空白 → 服务端生成 UUID（chatStream 同步段解析后透传编排）
     */
    @Test
    void chatStream_blankConversationId_generatesUuid() {
        stubCommon(AssistantIntent.FILE_LIST);
        stubAnswer("总投资约100万元。");
        when(projectService.listProjectFiles(eq(PROJECT_ID), any(PageQuery.class)))
                .thenReturn(PageResult.empty());

        AssistantChatResponse resp = finalResponseOf(runChat(req(MESSAGE)));

        assertThat(resp.getConversationId()).isNotBlank();
        assertThat(UUID.fromString(resp.getConversationId())).isNotNull();
    }

    /**
     * conversationId 非空 → 原样透传
     */
    @Test
    void chatStream_givenConversationId_passedThrough() {
        stubCommon(AssistantIntent.FILE_LIST);
        stubAnswer("总投资约100万元。");
        when(projectService.listProjectFiles(eq(PROJECT_ID), any(PageQuery.class)))
                .thenReturn(PageResult.empty());
        AssistantChatRequest request = req(MESSAGE);
        request.setConversationId("conv-001");

        AssistantChatResponse resp = finalResponseOf(runChat(request));

        assertThat(resp.getConversationId()).isEqualTo("conv-001");
    }

    // ==================== Phase 9 会话历史编排（追加约束 1/2/3/4/5） ====================

    /**
     * 历史按 max-injected-turns 读取并按时间序（最近一轮最后）传入意图识别（追加约束 3）
     */
    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void chatStream_historyQuestionsPassedToIntentAnalyzerInOrder() {
        when(projectService.getProjectById(PROJECT_ID)).thenReturn(projectVO());
        when(conversationHistoryStore.loadRecentTurns(eq(PROJECT_ID), eq("conv-001"), eq(6)))
                .thenReturn(List.of(
                        turn("这个项目总投资是多少？", "总投资约100万元。"),
                        turn("项目有哪些文件？", "共有2个文件。")));
        when(queryIntentAnalyzer.analyze(eq(MESSAGE), eq("示范项目"), anyList()))
                .thenReturn(analysis(AssistantIntent.FILE_LIST));
        stubAnswer("共有2个文件。");
        when(projectService.listProjectFiles(eq(PROJECT_ID), any(PageQuery.class)))
                .thenReturn(PageResult.empty());
        AssistantChatRequest request = req(MESSAGE);
        request.setConversationId("conv-001");

        runChat(request);

        ArgumentCaptor<List<String>> captor = ArgumentCaptor.forClass((Class) List.class);
        verify(queryIntentAnalyzer).analyze(eq(MESSAGE), eq("示范项目"), captor.capture());
        // 时间顺序：历史问题按存储序透传，最近一轮在最后
        assertThat(captor.getValue())
                .containsExactly("这个项目总投资是多少？", "项目有哪些文件？");
        verify(conversationHistoryStore).appendTurn(
                eq(PROJECT_ID), eq("conv-001"), eq(MESSAGE), eq("共有2个文件。"));
    }

    /**
     * 追加约束 2：standaloneQuestion 是本轮唯一有效问题——RAG query 与回答 Prompt
     * 用户问题均使用改写值；原始 message 仅忠实入历史记录
     */
    @Test
    void chatStream_rewrite_standaloneQuestionUsedForRagAndPrompt() {
        String rewritten = "这个项目的建筑面积是多少？";
        when(projectService.getProjectById(PROJECT_ID)).thenReturn(projectVO());
        when(conversationHistoryStore.loadRecentTurns(eq(PROJECT_ID), eq("conv-001"), eq(6)))
                .thenReturn(List.of(turn("这个项目总投资是多少？", "总投资约100万元。")));
        when(queryIntentAnalyzer.analyze(eq("那面积呢？"), eq("示范项目"), anyList()))
                .thenReturn(new AssistantIntentAnalysis(AssistantIntent.STRUCTURED, rewritten));
        when(projectQueryService.queryProjectFacts(PROJECT_ID)).thenReturn(facts());
        when(projectRetrievalService.retrieve(PROJECT_ID, rewritten, 5)).thenReturn(List.of());
        stubAnswer("建筑面积为100平米。");
        AssistantChatRequest request = req("那面积呢？");
        request.setConversationId("conv-001");

        AssistantChatResponse resp = finalResponseOf(runChat(request));

        // RAG query 使用改写问题（非原始"那面积呢？"）
        verify(projectRetrievalService).retrieve(PROJECT_ID, rewritten, 5);
        // 回答 Prompt 用户问题 = 同一个改写问题
        ArgumentCaptor<Prompt> promptCaptor = ArgumentCaptor.forClass(Prompt.class);
        verify(chatModel).stream(promptCaptor.capture());
        assertThat(userTextOf(promptCaptor.getValue()))
                .contains("用户问题：这个项目的建筑面积是多少？")
                .doesNotContain("用户问题：那面积呢？");
        // 历史记录忠实原文 message（非改写值）
        verify(conversationHistoryStore).appendTurn(
                PROJECT_ID, "conv-001", "那面积呢？", "建筑面积为100平米。");
        assertThat(resp.getAnswer()).isEqualTo("建筑面积为100平米。");
    }

    /**
     * 追加约束 1 链路验证：历史轮次完整注入回答 Prompt 的【对话历史】段
     * （仅指代消解语境，事实依据由本次 facts/RAG 提供）
     */
    @Test
    void chatStream_historyRenderedInAnswerPrompt() {
        when(projectService.getProjectById(PROJECT_ID)).thenReturn(projectVO());
        when(conversationHistoryStore.loadRecentTurns(eq(PROJECT_ID), eq("conv-001"), eq(6)))
                .thenReturn(List.of(turn("这个项目总投资是多少？", "总投资约100万元。")));
        when(queryIntentAnalyzer.analyze(eq(MESSAGE), eq("示范项目"), anyList()))
                .thenReturn(analysis(AssistantIntent.STRUCTURED));
        when(projectQueryService.queryProjectFacts(PROJECT_ID)).thenReturn(facts());
        stubRag(List.of());
        stubAnswer("总投资约100万元。");
        AssistantChatRequest request = req(MESSAGE);
        request.setConversationId("conv-001");

        runChat(request);

        ArgumentCaptor<Prompt> promptCaptor = ArgumentCaptor.forClass(Prompt.class);
        verify(chatModel).stream(promptCaptor.capture());
        assertThat(userTextOf(promptCaptor.getValue()))
                .contains("【对话历史】（仅用于理解当前问题的指代关系，不是项目事实来源）")
                .contains("用户：这个项目总投资是多少？")
                .contains("助手：总投资约100万元。");
    }

    // ==================== 测试辅助 ====================

    /**
     * 启动流式对话并同步执行编排（Phase K 测试模式）：捕获线程池提交的编排任务 →
     * 运行任务 → 返回记录的事件序列（sendEvent 已被匿名子类拦截，真实 SseEmitter
     * 不发送任何数据，无需初始化，不启 Spring 上下文、无真实网络 IO）。
     */
    private List<SentEvent> runChat(AssistantChatRequest request) {
        recordedEvents.clear();
        List<Runnable> submitted = new ArrayList<>();
        ReflectionTestUtils.setField(service, "assistantStreamExecutor", (Executor) submitted::add);
        service.chatStream(PROJECT_ID, request);
        submitted.forEach(Runnable::run);
        return recordedEvents;
    }

    /**
     * 提取 final 事件载荷（AssistantChatResponse），并断言存在且唯一
     */
    private AssistantChatResponse finalResponseOf(List<SentEvent> events) {
        List<SentEvent> finals = events.stream()
                .filter(e -> "final".equals(e.name())).toList();
        assertThat(finals).hasSize(1);
        return (AssistantChatResponse) finals.get(0).payload();
    }

    /**
     * 回答 LLM 流式输出打桩（Phase K）：单块增量 = 完整文本
     */
    private void stubAnswer(String text) {
        when(chatModel.stream(any(Prompt.class))).thenReturn(Flux.just(chatResponse(text)));
    }

    /**
     * 公共正向打桩：守门 + 会话历史（首轮空历史）+ 意图识别（检索/事实/清单/LLM
     * 回答按用例意图单独打桩，避免出现未使用桩触发严格桩检查失败）
     */
    private void stubCommon(AssistantIntent intent) {
        when(projectService.getProjectById(PROJECT_ID)).thenReturn(projectVO());
        when(conversationHistoryStore.loadRecentTurns(eq(PROJECT_ID), anyString(), eq(6)))
                .thenReturn(List.of());
        when(queryIntentAnalyzer.analyze(eq(MESSAGE), eq("示范项目"), anyList()))
                .thenReturn(analysis(intent));
    }

    /**
     * Phase 9 意图分析结果：standaloneQuestion 默认 = MESSAGE，
     * 对应"问题已独立，改写不改变语义"的等价假设
     */
    private AssistantIntentAnalysis analysis(AssistantIntent intent) {
        return new AssistantIntentAnalysis(intent, MESSAGE);
    }

    /**
     * Phase 9 对话轮次构造
     */
    private ConversationTurn turn(String question, String answer) {
        return ConversationTurn.builder()
                .userQuestion(question)
                .assistantAnswer(answer)
                .createTime(java.time.LocalDateTime.now())
                .build();
    }

    /**
     * 提取 Prompt 中的 UserMessage 文本（回答/意图 Prompt 内容断言用）
     */
    private String userTextOf(Prompt prompt) {
        return prompt.getInstructions().stream()
                .filter(UserMessage.class::isInstance)
                .map(UserMessage.class::cast)
                .map(UserMessage::getText)
                .findFirst()
                .orElse("");
    }

    /**
     * 已录制 SSE 事件（name=事件名，payload=sendEvent 收到的原始载荷对象）
     */
    private record SentEvent(String name, Object payload) {
    }

    /**
     * RAG 检索打桩（DOCUMENT/STRUCTURED/HYBRID/UNKNOWN 用例使用）
     */
    private void stubRag(List<Document> hits) {
        when(projectRetrievalService.retrieve(PROJECT_ID, MESSAGE, 5)).thenReturn(hits);
    }

    private AssistantChatRequest req(String message) {
        AssistantChatRequest request = new AssistantChatRequest();
        request.setMessage(message);
        return request;
    }

    private ProjectVO projectVO() {
        ProjectVO vo = new ProjectVO();
        vo.setProjectId(PROJECT_ID);
        vo.setProjectName("示范项目");
        return vo;
    }

    /**
     * 单实例单字段双来源事实（未标冲突——组装逻辑与冲突标记无关）
     */
    private ProjectStructuredFactsVO facts() {
        ProjectStructuredFactsVO.Field field = new ProjectStructuredFactsVO.Field();
        field.setProjectFormId(PROJECT_FORM_ID);
        field.setFieldCode("total_investment");
        field.setFieldName("总投资金额");
        field.setFieldType(FieldType.DECIMAL.getCode());
        field.getValues().add(value("100万", "1000000", FILE_A, "预算说明书.pdf"));
        field.getValues().add(value("200万", "2000000", FILE_B, "可研报告.pdf"));
        ProjectStructuredFactsVO.Form form = new ProjectStructuredFactsVO.Form();
        form.setProjectFormId(PROJECT_FORM_ID);
        form.getFields().add(field);
        ProjectStructuredFactsVO facts = new ProjectStructuredFactsVO();
        facts.getForms().add(form);
        return facts;
    }

    private ProjectStructuredFactsVO.ValueItem value(String raw, String normalized,
                                                     Long fileId, String fileName) {
        ProjectStructuredFactsVO.ValueItem item = new ProjectStructuredFactsVO.ValueItem();
        item.setRawValue(raw);
        item.setNormalizedValue(normalized);
        item.setUnit("万元");
        item.setSourceFileId(fileId);
        item.setSourceFileName(fileName);
        item.setSourcePage(3);
        return item;
    }

    /**
     * RAG 命中切片（Phase 5 约定 metadata 值 String 化）
     */
    private Document ragDoc() {
        return new Document("相关原文内容", Map.of(
                "fileId", String.valueOf(FILE_A),
                "fileName", "预算说明书.pdf",
                "pageStart", "3"));
    }

    private PageResult<FileRecordVO> filesPage() {
        FileRecordVO vo = new FileRecordVO();
        vo.setFileId(FILE_A);
        vo.setFileName("预算说明书.pdf");
        return PageResult.of(1, 1, 1, 100, List.of(vo));
    }

    private ChatResponse chatResponse(String content) {
        return new ChatResponse(List.of(new Generation(new AssistantMessage(content))));
    }

    /**
     * 跨项目比较结果样本（Phase 10）：1 参与单元（目标项目，来源 FILE_B）+
     * 1 排除单元（当前项目冲突，来源 FILE_A）
     */
    private CrossProjectComparisonVO comparisonVO() {
        CrossProjectComparisonVO.Unit unit = new CrossProjectComparisonVO.Unit();
        unit.setProjectId(TARGET_PROJECT_ID);
        unit.setProjectName("项目B");
        unit.setProjectFormId(TARGET_FORM_ID);
        unit.setRawValue("200万");
        unit.setNormalizedValue("2000000");
        unit.setUnit("万元");
        unit.setRank(1);
        unit.setSourceFileId(FILE_B);
        unit.setSourceFileName("项目B预算.pdf");
        unit.setSourcePage(2);

        ProjectStructuredFactsVO.ValueItem excludedValue = new ProjectStructuredFactsVO.ValueItem();
        excludedValue.setRawValue("300万");
        excludedValue.setNormalizedValue("3000000");
        excludedValue.setUnit("万元");
        excludedValue.setSourceFileId(FILE_A);
        excludedValue.setSourceFileName("预算说明书.pdf");
        CrossProjectComparisonVO.ExcludedUnit excluded = new CrossProjectComparisonVO.ExcludedUnit();
        excluded.setReason("CONFLICT");
        excluded.setProjectId(PROJECT_ID);
        excluded.setProjectName("示范项目");
        excluded.setProjectFormId(PROJECT_FORM_ID);
        excluded.setFieldCode("total_investment");
        excluded.getValues().add(excludedValue);

        CrossProjectComparisonVO comparison = new CrossProjectComparisonVO();
        comparison.setFieldCode("total_investment");
        comparison.setFieldName("总投资金额");
        comparison.setFieldType(FieldType.DECIMAL.getCode());
        comparison.setCurrentProjectId(PROJECT_ID);
        comparison.getTargetProjectIds().add(TARGET_PROJECT_ID);
        comparison.getUnits().add(unit);
        comparison.getExcluded().add(excluded);
        return comparison;
    }
}
