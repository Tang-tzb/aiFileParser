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
 * {@link QueryIntentAnalyzer} 测试（离线，Phase 8 T1）
 * <p>
 * 覆盖追加约束 11 错误边界：合法 JSON/带围栏 JSON → 对应枚举；非法 intent 值/
 * 缺键/非 JSON → 降级 UNKNOWN（不抛 3002）；ChatModel 异常 → 3001 早失败。
 * 另覆盖追加约束 4：User Prompt 仅含 projectName + question，无任何项目事实内容。
 *
 * @author Tang_tzb
 */
@ExtendWith(MockitoExtension.class)
class QueryIntentAnalyzerTest {

    private static final String QUESTION = "这个项目总投资是多少？";
    private static final String PROJECT_NAME = "示范项目";

    @Mock
    private ChatModel chatModel;

    private QueryIntentAnalyzer analyzer;

    @BeforeEach
    void setUp() {
        // 真实 ObjectMapper 验证端到端 JSON 解析语义
        analyzer = new QueryIntentAnalyzer(chatModel, new ObjectMapper());
    }

    // ==================== 合法输出 → 对应枚举 ====================

    /**
     * 严格单键 JSON → 对应意图
     */
    @Test
    void validJson_returnsMatchingIntent() {
        stubModelResponse("{\"intent\":\"STRUCTURED\"}");

        assertThat(analyzer.analyze(QUESTION, PROJECT_NAME)).isEqualTo(AssistantIntent.STRUCTURED);
    }

    /**
     * 模型输出带 ```json 围栏 → 正常剥离解析
     */
    @Test
    void fencedJson_returnsMatchingIntent() {
        stubModelResponse("```json\n{\"intent\":\"FILE_LIST\"}\n```");

        assertThat(analyzer.analyze(QUESTION, PROJECT_NAME)).isEqualTo(AssistantIntent.FILE_LIST);
    }

    /**
     * 全部六个意图均可按枚举名解析（含 UNSUPPORTED/UNKNOWN 本身）
     */
    @Test
    void allEnumNames_parseable() {
        for (AssistantIntent intent : AssistantIntent.values()) {
            stubModelResponse("{\"intent\":\"" + intent.name() + "\"}");
            assertThat(analyzer.analyze(QUESTION, PROJECT_NAME)).isEqualTo(intent);
        }
    }

    // ==================== 非法输出 → UNKNOWN 降级（追加约束 11） ====================

    /**
     * intent 值不在枚举内 → UNKNOWN（不抛 3002）
     */
    @Test
    void unknownIntentValue_degradesToUnknown() {
        stubModelResponse("{\"intent\":\"NOT_A_INTENT\"}");

        assertThat(analyzer.analyze(QUESTION, PROJECT_NAME)).isEqualTo(AssistantIntent.UNKNOWN);
    }

    /**
     * 缺 intent 键 → UNKNOWN
     */
    @Test
    void missingIntentKey_degradesToUnknown() {
        stubModelResponse("{\"other\":\"STRUCTURED\"}");

        assertThat(analyzer.analyze(QUESTION, PROJECT_NAME)).isEqualTo(AssistantIntent.UNKNOWN);
    }

    /**
     * 非法 JSON（模型跑题输出自然语言）→ UNKNOWN
     */
    @Test
    void nonJsonOutput_degradesToUnknown() {
        stubModelResponse("抱歉，我无法分类这个问题。");

        assertThat(analyzer.analyze(QUESTION, PROJECT_NAME)).isEqualTo(AssistantIntent.UNKNOWN);
    }

    /**
     * 空白输出 → UNKNOWN
     */
    @Test
    void blankOutput_degradesToUnknown() {
        stubModelResponse("  ");

        assertThat(analyzer.analyze(QUESTION, PROJECT_NAME)).isEqualTo(AssistantIntent.UNKNOWN);
    }

    // ==================== ChatModel 异常 → 3001（追加约束 11） ====================

    /**
     * 模型调用异常 → AI_INVOKE_ERROR(3001) 早失败（后续回答调用也必然失败）
     */
    @Test
    void chatModelFailure_throwsAiInvokeError3001() {
        when(chatModel.call(any(Prompt.class))).thenThrow(new RuntimeException("connection refused"));

        assertThatThrownBy(() -> analyzer.analyze(QUESTION, PROJECT_NAME))
                .isInstanceOfSatisfying(BusinessException.class, e ->
                        assertThat(e.getCode()).isEqualTo(ResultCode.AI_INVOKE_ERROR.getCode()));
    }

    // ==================== Prompt 轻量约束（追加约束 4） ====================

    /**
     * User Prompt 仅两行：项目名称 + 用户问题，不携带任何事实/文档内容；
     * System Prompt 含六个意图判定标准与单键 JSON 输出契约
     */
    @Test
    @SuppressWarnings("unchecked")
    void prompts_lightweightUserMessageWithProjectNameAndQuestion() {
        stubModelResponse("{\"intent\":\"DOCUMENT\"}");

        analyzer.analyze(QUESTION, PROJECT_NAME);

        ArgumentCaptor<Prompt> captor = ArgumentCaptor.forClass(Prompt.class);
        verify(chatModel).call(captor.capture());
        List<Message> messages = captor.getValue().getInstructions();
        assertThat(messages).hasSize(2);
        String system = messages.get(0).getText();
        assertThat(system)
                .contains("STRUCTURED").contains("UNSUPPORTED").contains("UNKNOWN")
                .contains("{\"intent\":");
        String user = ((UserMessage) messages.get(1)).getText();
        assertThat(user)
                .isEqualTo("项目名称：" + PROJECT_NAME + "\n用户问题：" + QUESTION);
    }

    // ==================== 测试辅助 ====================

    private void stubModelResponse(String content) {
        when(chatModel.call(any(Prompt.class))).thenReturn(chatResponse(content));
    }

    private ChatResponse chatResponse(String content) {
        return new ChatResponse(List.of(new Generation(new AssistantMessage(content))));
    }
}
