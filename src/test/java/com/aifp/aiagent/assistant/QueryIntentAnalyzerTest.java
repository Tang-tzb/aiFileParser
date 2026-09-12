package com.aifp.aiagent.assistant;

import com.aifp.aiagent.common.ResultCode;
import com.aifp.aiagent.exception.BusinessException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link QueryIntentAnalyzer} 测试（离线；Phase 8 T1 / Phase 9 契约升级适配）
 * <p>
 * Phase 9 双键契约：{@code {"intent":...,"question":改写后独立问题}}；
 * 覆盖追加约束 11 错误边界（非法输出→UNKNOWN+原始问题兜底、ChatModel 异常→3001）、
 * 追加约束 5（User Prompt 仅 projectName+历史用户问题+question，不注入答案/事实）、
 * 追加约束 2（question 缺失兜底原始问题，不编造改写）。
 *
 * @author Tang_tzb
 */
@ExtendWith(MockitoExtension.class)
class QueryIntentAnalyzerTest {

    private static final String QUESTION = "这个项目总投资是多少？";
    private static final String PROJECT_NAME = "示范项目";
    private static final List<String> HISTORY_QUESTIONS =
            List.of("这个项目总投资是多少？", "项目有哪些文件？");

    @Mock
    private ChatModel chatModel;

    private QueryIntentAnalyzer analyzer;

    @BeforeEach
    void setUp() {
        // 真实 ObjectMapper 验证端到端 JSON 解析语义
        analyzer = new QueryIntentAnalyzer(chatModel, new ObjectMapper());
    }

    // ==================== 合法输出 → 意图 + 改写问题 ====================

    /**
     * 双键 JSON → intent + standaloneQuestion 均解析
     */
    @Test
    void validJson_returnsIntentAndStandaloneQuestion() {
        stubModelResponse("{\"intent\":\"STRUCTURED\",\"question\":\"这个项目的总投资是多少？\"}");

        AssistantIntentAnalysis result = analyzer.analyze(QUESTION, PROJECT_NAME, List.of());

        assertThat(result.getIntent()).isEqualTo(AssistantIntent.STRUCTURED);
        assertThat(result.getStandaloneQuestion()).isEqualTo("这个项目的总投资是多少？");
    }

    /**
     * 模型输出带 ```json 围栏 → 正常剥离解析
     */
    @Test
    void fencedJson_parsable() {
        stubModelResponse("```json\n{\"intent\":\"FILE_LIST\",\"question\":\"" + QUESTION + "\"}\n```");

        AssistantIntentAnalysis result = analyzer.analyze(QUESTION, PROJECT_NAME, List.of());

        assertThat(result.getIntent()).isEqualTo(AssistantIntent.FILE_LIST);
    }

    /**
     * 全部六个意图均可按枚举名解析（含 UNSUPPORTED/UNKNOWN 本身）
     */
    @Test
    void allEnumNames_parseable() {
        for (AssistantIntent intent : AssistantIntent.values()) {
            stubModelResponse("{\"intent\":\"" + intent.name() + "\",\"question\":\"" + QUESTION + "\"}");
            assertThat(analyzer.analyze(QUESTION, PROJECT_NAME, List.of()).getIntent()).isEqualTo(intent);
        }
    }

    /**
     * 追问改写：结合历史用户问题解析指代（追加约束 2 的核心场景）
     */
    @Test
    void followUpRewrite_resolvedFromHistory() {
        stubModelResponse("{\"intent\":\"STRUCTURED\",\"question\":\"这个项目的建筑面积是多少？\"}");

        AssistantIntentAnalysis result = analyzer.analyze(
                "那面积呢？", PROJECT_NAME, HISTORY_QUESTIONS);

        assertThat(result.getIntent()).isEqualTo(AssistantIntent.STRUCTURED);
        assertThat(result.getStandaloneQuestion()).isEqualTo("这个项目的建筑面积是多少？");
    }

    // ==================== 非法输出 → 降级（追加约束 11） ====================

    /**
     * intent 值不在枚举内 → UNKNOWN + 原始问题兜底（不抛 3002）
     */
    @Test
    void unknownIntentValue_degradesToUnknownWithOriginalQuestion() {
        stubModelResponse("{\"intent\":\"NOT_A_INTENT\",\"question\":\"随便改\"}");

        AssistantIntentAnalysis result = analyzer.analyze(QUESTION, PROJECT_NAME, List.of());

        assertThat(result.getIntent()).isEqualTo(AssistantIntent.UNKNOWN);
        assertThat(result.getStandaloneQuestion()).isEqualTo(QUESTION);
    }

    /**
     * 缺 intent 键 → UNKNOWN + 原始问题兜底
     */
    @Test
    void missingIntentKey_degradesToUnknown() {
        stubModelResponse("{\"other\":\"STRUCTURED\"}");

        AssistantIntentAnalysis result = analyzer.analyze(QUESTION, PROJECT_NAME, List.of());

        assertThat(result.getIntent()).isEqualTo(AssistantIntent.UNKNOWN);
        assertThat(result.getStandaloneQuestion()).isEqualTo(QUESTION);
    }

    /**
     * question 缺失/空白 → intent 照常生效 + 原始问题兜底（追加约束 2：不编造改写）
     */
    @Test
    void missingQuestionKey_intentIntactWithOriginalQuestion() {
        stubModelResponse("{\"intent\":\"DOCUMENT\"}");

        AssistantIntentAnalysis result = analyzer.analyze(QUESTION, PROJECT_NAME, List.of());

        assertThat(result.getIntent()).isEqualTo(AssistantIntent.DOCUMENT);
        assertThat(result.getStandaloneQuestion()).isEqualTo(QUESTION);
    }

    /**
     * 非法 JSON（模型跑题输出自然语言）→ UNKNOWN + 原始问题兜底
     */
    @Test
    void nonJsonOutput_degradesToUnknown() {
        stubModelResponse("抱歉，我无法分类这个问题。");

        AssistantIntentAnalysis result = analyzer.analyze(QUESTION, PROJECT_NAME, List.of());

        assertThat(result.getIntent()).isEqualTo(AssistantIntent.UNKNOWN);
        assertThat(result.getStandaloneQuestion()).isEqualTo(QUESTION);
    }

    // ==================== ChatModel 异常 → 3001（追加约束 11） ====================

    /**
     * 模型调用异常 → AI_INVOKE_ERROR(3001) 早失败（后续回答调用也必然失败）
     */
    @Test
    void chatModelFailure_throwsAiInvokeError3001() {
        when(chatModel.call(any(Prompt.class))).thenThrow(new RuntimeException("connection refused"));

        assertThatThrownBy(() -> analyzer.analyze(QUESTION, PROJECT_NAME, List.of()))
                .isInstanceOfSatisfying(BusinessException.class, e ->
                        assertThat(e.getCode()).isEqualTo(ResultCode.AI_INVOKE_ERROR.getCode()));
    }

    // ==================== Prompt 轻量约束（追加约束 5） ====================

    /**
     * 无历史：User Prompt 仅项目名称 + 用户问题两行；
     * System Prompt 含六意图判定标准、改写规则与双键 JSON 输出契约
     */
    @Test
    @SuppressWarnings("unchecked")
    void prompts_withoutHistory_lightweightTwoLines() {
        stubModelResponse("{\"intent\":\"DOCUMENT\",\"question\":\"" + QUESTION + "\"}");

        analyzer.analyze(QUESTION, PROJECT_NAME, List.of());

        ArgumentCaptor<Prompt> captor = ArgumentCaptor.forClass(Prompt.class);
        verify(chatModel).call(captor.capture());
        List<Message> messages = captor.getValue().getInstructions();
        assertThat(messages).hasSize(2);
        String system = messages.get(0).getText();
        assertThat(system)
                .contains("STRUCTURED").contains("UNSUPPORTED").contains("UNKNOWN")
                .contains("{\"intent\":")
                .contains("禁止编造或扩展问题内容");
        String user = ((UserMessage) messages.get(1)).getText();
        assertThat(user)
                .isEqualTo("项目名称：" + PROJECT_NAME + "\n用户问题：" + QUESTION);
    }

    /**
     * 有历史：User Prompt 注入历史用户问题段（仅问题文本——追加约束 5，
     * 不含助手答案/事实/文档内容），时间顺序保持
     */
    @Test
    @SuppressWarnings("unchecked")
    void prompts_withHistory_injectsRecentQuestionsOnly() {
        stubModelResponse("{\"intent\":\"STRUCTURED\",\"question\":\"改写后\"}");

        analyzer.analyze(QUESTION, PROJECT_NAME, HISTORY_QUESTIONS);

        ArgumentCaptor<Prompt> captor = ArgumentCaptor.forClass(Prompt.class);
        verify(chatModel).call(captor.capture());
        String user = ((UserMessage) captor.getValue().getInstructions().get(1)).getText();
        assertThat(user)
                .isEqualTo("项目名称：" + PROJECT_NAME
                        + "\n对话历史中的用户问题（仅用于理解当前问题指代）：\n"
                        + "- 这个项目总投资是多少？\n"
                        + "- 项目有哪些文件？\n"
                        + "用户问题：" + QUESTION);
    }

    // ==================== 测试辅助 ====================

    private void stubModelResponse(String content) {
        when(chatModel.call(any(Prompt.class))).thenReturn(chatResponse(content));
    }

    private ChatResponse chatResponse(String content) {
        return new ChatResponse(List.of(new Generation(new AssistantMessage(content))));
    }
}
