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

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.anyInt;
import static org.mockito.Mockito.*;

/**
 * {@link ProjectAssistantServiceImpl} 测试（离线，Phase 8 T4 / Phase 9 会话编排适配）
 * <p>
 * Phase 8 覆盖：参数防御 400；守门 403/6001 透传；UNSUPPORTED 真短路（追加约束 5，
 * verify 零 LLM/零检索/零事实查询/零文件清单）；五意图数据源路由（STRUCTURED 恒
 * facts+RAG 追加约束 2）；conversationId 空生成/非空透传（追加约束 8 无状态）；
 * 回答 LLM 异常/空白 → 3001 不重进 RAG（追加约束 11）；references/structuredData/
 * usedFiles 组装（追加约束 3/12，后端组装非 LLM 生成）。
 * <p>
 * Phase 9 追加覆盖：历史按 max-injected-turns 读取并按时间序传入意图识别（约束 3）；
 * standaloneQuestion 同时用于 RAG query 与回答 Prompt、原始 message 忠实入历史（约束 2）；
 * UNSUPPORTED 记录实际返回固定文案、3001 失败零轮次记录（约束 5）；成功轮次后置
 * appendTurn（约束 4）。
 * QueryPlanner/PromptBuilder/ChunkMetadataReader 为纯函数/渲染组件使用真实实例。
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

    @BeforeEach
    void setUp() {
        service = new ProjectAssistantServiceImpl(
                projectService, projectQueryService, projectRetrievalService,
                queryIntentAnalyzer, new QueryPlanner(),
                new AssistantPromptBuilder(new ChunkMetadataReader()),
                new ChunkMetadataReader(), conversationHistoryStore,
                comparisonService, chatModel);
        ReflectionTestUtils.setField(service, "assistantTopK", 5);
        ReflectionTestUtils.setField(service, "maxInjectedTurns", 6);
    }

    // ==================== 参数防御与守门 ====================

    /**
     * message 空白 → 400，快速失败不触碰任何依赖
     */
    @Test
    void chat_blankMessage_throwsParamError400() {
        assertThatThrownBy(() -> service.chat(PROJECT_ID, req("   ")))
                .isInstanceOfSatisfying(BusinessException.class, e ->
                        assertThat(e.getCode()).isEqualTo(ResultCode.PARAM_ERROR.getCode()));
        verifyNoInteractions(projectService, projectQueryService,
                projectRetrievalService, queryIntentAnalyzer, conversationHistoryStore, chatModel);
    }

    /**
     * 守门唯一入口 ProjectService（403）：意图识别前拦截
     */
    @Test
    void chat_accessDenied_propagates403() {
        when(projectService.getProjectById(PROJECT_ID)).thenThrow(new BusinessException(ResultCode.FORBIDDEN));

        assertThatThrownBy(() -> service.chat(PROJECT_ID, req(MESSAGE)))
                .isInstanceOfSatisfying(BusinessException.class, e ->
                        assertThat(e.getCode()).isEqualTo(ResultCode.FORBIDDEN.getCode()));
        verifyNoInteractions(queryIntentAnalyzer, projectQueryService,
                projectRetrievalService, conversationHistoryStore, chatModel);
    }

    /**
     * 守门唯一入口 ProjectService（6001）
     */
    @Test
    void chat_projectMissing_propagates6001() {
        when(projectService.getProjectById(PROJECT_ID)).thenThrow(
                new BusinessException(ResultCode.PROJECT_NOT_FOUND));

        assertThatThrownBy(() -> service.chat(PROJECT_ID, req(MESSAGE)))
                .isInstanceOfSatisfying(BusinessException.class, e ->
                        assertThat(e.getCode()).isEqualTo(ResultCode.PROJECT_NOT_FOUND.getCode()));
        // 守门前零 Redis 访问（追加约束 6）
        verifyNoInteractions(queryIntentAnalyzer, projectQueryService,
                projectRetrievalService, conversationHistoryStore, chatModel);
    }

    /**
     * 意图识别 ChatModel 异常（3001）直接上抛，不进入任何检索；
     * 追加约束 5：模型失败零轮次记录
     */
    @Test
    void chat_intentAnalyzerFailure_propagates3001() {
        when(projectService.getProjectById(PROJECT_ID)).thenReturn(projectVO());
        when(conversationHistoryStore.loadRecentTurns(eq(PROJECT_ID), anyString(), eq(6)))
                .thenReturn(List.of());
        when(queryIntentAnalyzer.analyze(eq(MESSAGE), eq("示范项目"), anyList())).thenThrow(
                new BusinessException(ResultCode.AI_INVOKE_ERROR, "AI 意图识别调用失败"));

        assertThatThrownBy(() -> service.chat(PROJECT_ID, req(MESSAGE)))
                .isInstanceOfSatisfying(BusinessException.class, e ->
                        assertThat(e.getCode()).isEqualTo(ResultCode.AI_INVOKE_ERROR.getCode()));
        verify(conversationHistoryStore, never())
                .appendTurn(any(), anyString(), anyString(), anyString());
        verifyNoInteractions(projectQueryService, projectRetrievalService, chatModel);
    }

    // ==================== UNSUPPORTED 真短路（追加约束 5/14） ====================

    /**
     * UNSUPPORTED → 意图识别后立即返回确定性文案，零 LLM 零检索零事实查询零文件清单；
     * Phase 10 文案收窄（追加约束 13）：仅归因分析不支持；追加约束 5：记录真实交互
     * （assistantAnswer = 实际返回给用户的固定文案）
     */
    @Test
    void chat_unsupported_shortCircuitWithDeterministicAnswer() {
        when(projectService.getProjectById(PROJECT_ID)).thenReturn(projectVO());
        when(conversationHistoryStore.loadRecentTurns(eq(PROJECT_ID), anyString(), eq(6)))
                .thenReturn(List.of());
        when(queryIntentAnalyzer.analyze(eq(MESSAGE), eq("示范项目"), anyList()))
                .thenReturn(analysis(AssistantIntent.UNSUPPORTED));

        AssistantChatResponse resp = service.chat(PROJECT_ID, req(MESSAGE));

        assertThat(resp.getAnswer())
                .contains("当前版本暂不支持项目之间的归因分析与原因解释");
        assertThat(resp.getReferences()).isEmpty();
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
    void chat_structured_factsAndRagAlwaysFetched() {
        stubCommon(AssistantIntent.STRUCTURED);
        stubRag(List.of());
        stubAnswer("总投资约100万元。");
        when(projectQueryService.queryProjectFacts(PROJECT_ID)).thenReturn(facts());

        AssistantChatResponse resp = service.chat(PROJECT_ID, req(MESSAGE));

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
    void chat_document_ragOnlyWithFileReferences() {
        stubCommon(AssistantIntent.DOCUMENT);
        stubRag(List.of(ragDoc()));
        stubAnswer("总投资约100万元。");

        AssistantChatResponse resp = service.chat(PROJECT_ID, req(MESSAGE));

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
    void chat_fileList_fileNamesOnly() {
        stubCommon(AssistantIntent.FILE_LIST);
        stubAnswer("总投资约100万元。");
        when(projectService.listProjectFiles(eq(PROJECT_ID), any(PageQuery.class)))
                .thenReturn(filesPage());

        AssistantChatResponse resp = service.chat(PROJECT_ID, req(MESSAGE));

        ArgumentCaptor<PageQuery> captor = ArgumentCaptor.forClass(PageQuery.class);
        verify(projectService).listProjectFiles(eq(PROJECT_ID), captor.capture());
        // 追加约束 7：PageQuery(1,100) 当前版本限制
        assertThat(captor.getValue().getPageNum()).isEqualTo(1);
        assertThat(captor.getValue().getPageSize()).isEqualTo(100);
        verifyNoInteractions(projectQueryService, projectRetrievalService);
        assertThat(resp.getReferences()).isEmpty();
        assertThat(resp.getUsedFiles()).isEmpty();
    }

    /**
     * UNKNOWN → 保守降级事实 + RAG 双通道（§十六）
     */
    @Test
    void chat_unknown_factsAndRag() {
        stubCommon(AssistantIntent.UNKNOWN);
        stubRag(List.of());
        stubAnswer("总投资约100万元。");
        when(projectQueryService.queryProjectFacts(PROJECT_ID)).thenReturn(facts());

        service.chat(PROJECT_ID, req(MESSAGE));

        verify(projectQueryService).queryProjectFacts(PROJECT_ID);
        verify(projectRetrievalService).retrieve(PROJECT_ID, MESSAGE, 5);
    }

    /**
     * HYBRID → 事实 + RAG
     */
    @Test
    void chat_hybrid_factsAndRag() {
        stubCommon(AssistantIntent.HYBRID);
        stubRag(List.of());
        stubAnswer("总投资约100万元。");
        when(projectQueryService.queryProjectFacts(PROJECT_ID)).thenReturn(facts());

        service.chat(PROJECT_ID, req(MESSAGE));

        verify(projectQueryService).queryProjectFacts(PROJECT_ID);
        verify(projectRetrievalService).retrieve(PROJECT_ID, MESSAGE, 5);
    }

    // ==================== Phase 10 COMPARISON 分支 ====================

    /**
     * COMPARISON 成功路径：比较编排先行（不拉单项目全量事实）→ RAG 仅解释依据 →
     * 最终 LLM 只能引用已算结果；comparisonData 返回、比较证据并入
     * references/usedFiles、usedProjects = 当前 ∪ 目标（追加约束 6/9/11）
     */
    @Test
    void chat_comparison_success_returnsComparisonDataWithEvidence() {
        stubCommon(AssistantIntent.COMPARISON);
        CrossProjectComparisonVO comparison = comparisonVO();
        when(comparisonService.compare(PROJECT_ID, MESSAGE))
                .thenReturn(ProjectComparisonService.ComparisonOutcome.of(comparison));
        stubRag(List.of(ragDoc()));
        stubAnswer("项目B投资最高。");

        AssistantChatResponse resp = service.chat(PROJECT_ID, req(MESSAGE));

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
     * COMPARISON 降级路径（追加约束 2/12）：确定性文案直接返回——零后续 RAG、
     * 零最终 LLM、零事实查询；comparisonData=null；确定性答案忠实入历史
     */
    @Test
    void chat_comparison_fallback_deterministicAnswerWithoutRagOrLlm() {
        stubCommon(AssistantIntent.COMPARISON);
        String fallback = "无法确定比较范围：问题未明确参与比较的项目，请补充项目名称。";
        when(comparisonService.compare(PROJECT_ID, MESSAGE))
                .thenReturn(ProjectComparisonService.ComparisonOutcome.fallback(fallback));

        AssistantChatResponse resp = service.chat(PROJECT_ID, req(MESSAGE));

        assertThat(resp.getAnswer()).isEqualTo(fallback);
        assertThat(resp.getComparisonData()).isNull();
        assertThat(resp.getReferences()).isEmpty();
        assertThat(resp.getStructuredData()).isEmpty();
        assertThat(resp.getUsedProjects()).containsExactly(PROJECT_ID);
        verifyNoInteractions(projectRetrievalService, projectQueryService, chatModel);
        verify(conversationHistoryStore).appendTurn(
                eq(PROJECT_ID), anyString(), eq(MESSAGE), eq(fallback));
    }

    // ==================== 回答 LLM 错误边界（追加约束 11） ====================

    /**
     * 回答 ChatModel 异常 → 3001，禁止降级 UNKNOWN 或重进 RAG
     */
    @Test
    void chat_answerModelFailure_throwsAiInvokeError3001() {
        stubCommon(AssistantIntent.STRUCTURED);
        stubRag(List.of());
        when(projectQueryService.queryProjectFacts(PROJECT_ID)).thenReturn(facts());
        // 回答桩覆盖验证：异常路径不依赖 stubAnswer
        when(chatModel.call(any(Prompt.class))).thenThrow(new RuntimeException("timeout"));

        assertThatThrownBy(() -> service.chat(PROJECT_ID, req(MESSAGE)))
                .isInstanceOfSatisfying(BusinessException.class, e ->
                        assertThat(e.getCode()).isEqualTo(ResultCode.AI_INVOKE_ERROR.getCode()));
        // 不重试：事实与检索各只查询一次
        verify(projectQueryService, times(1)).queryProjectFacts(PROJECT_ID);
        verify(projectRetrievalService, times(1)).retrieve(any(Long.class), any(), anyInt());
        // 追加约束 5：3001 模型失败不得记录不完整轮次
        verify(conversationHistoryStore, never())
                .appendTurn(any(), anyString(), anyString(), anyString());
    }

    /**
     * 回答空白 → 3001（"AI 未返回有效回答"），不重进 RAG
     */
    @Test
    void chat_blankAnswer_throwsAiInvokeError3001() {
        stubCommon(AssistantIntent.STRUCTURED);
        stubRag(List.of());
        when(projectQueryService.queryProjectFacts(PROJECT_ID)).thenReturn(facts());
        when(chatModel.call(any(Prompt.class))).thenReturn(chatResponse("   "));

        assertThatThrownBy(() -> service.chat(PROJECT_ID, req(MESSAGE)))
                .isInstanceOfSatisfying(BusinessException.class, e ->
                        assertThat(e.getCode()).isEqualTo(ResultCode.AI_INVOKE_ERROR.getCode()));
        verify(conversationHistoryStore, never())
                .appendTurn(any(), anyString(), anyString(), anyString());
    }

    // ==================== 会话无状态（追加约束 8） ====================

    /**
     * conversationId 空白 → 服务端生成 UUID（不读 ChatMemory，无状态）
     */
    @Test
    void chat_blankConversationId_generatesUuid() {
        stubCommon(AssistantIntent.FILE_LIST);
        stubAnswer("总投资约100万元。");
        when(projectService.listProjectFiles(eq(PROJECT_ID), any(PageQuery.class)))
                .thenReturn(PageResult.empty());

        AssistantChatResponse resp = service.chat(PROJECT_ID, req(MESSAGE));

        assertThat(resp.getConversationId()).isNotBlank();
        assertThat(UUID.fromString(resp.getConversationId())).isNotNull();
    }

    /**
     * conversationId 非空 → 原样透传
     */
    @Test
    void chat_givenConversationId_passedThrough() {
        stubCommon(AssistantIntent.FILE_LIST);
        stubAnswer("总投资约100万元。");
        when(projectService.listProjectFiles(eq(PROJECT_ID), any(PageQuery.class)))
                .thenReturn(PageResult.empty());
        AssistantChatRequest request = req(MESSAGE);
        request.setConversationId("conv-001");

        AssistantChatResponse resp = service.chat(PROJECT_ID, request);

        assertThat(resp.getConversationId()).isEqualTo("conv-001");
    }

    // ==================== Phase 9 会话历史编排（追加约束 1/2/3/4/5） ====================

    /**
     * 历史按 max-injected-turns 读取并按时间序（最近一轮最后）传入意图识别（追加约束 3）
     */
    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void chat_historyQuestionsPassedToIntentAnalyzerInOrder() {
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

        service.chat(PROJECT_ID, request);

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
    void chat_rewrite_standaloneQuestionUsedForRagAndPrompt() {
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

        AssistantChatResponse resp = service.chat(PROJECT_ID, request);

        // RAG query 使用改写问题（非原始"那面积呢？"）
        verify(projectRetrievalService).retrieve(PROJECT_ID, rewritten, 5);
        // 回答 Prompt 用户问题 = 同一个改写问题
        ArgumentCaptor<Prompt> promptCaptor = ArgumentCaptor.forClass(Prompt.class);
        verify(chatModel).call(promptCaptor.capture());
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
    void chat_historyRenderedInAnswerPrompt() {
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

        service.chat(PROJECT_ID, request);

        ArgumentCaptor<Prompt> promptCaptor = ArgumentCaptor.forClass(Prompt.class);
        verify(chatModel).call(promptCaptor.capture());
        assertThat(userTextOf(promptCaptor.getValue()))
                .contains("【对话历史】（仅用于理解当前问题的指代关系，不是项目事实来源）")
                .contains("用户：这个项目总投资是多少？")
                .contains("助手：总投资约100万元。");
    }

    // ==================== 测试辅助 ====================

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
     * 回答 LLM 正常输出打桩
     */
    private void stubAnswer(String text) {
        when(chatModel.call(any(Prompt.class))).thenReturn(chatResponse(text));
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
