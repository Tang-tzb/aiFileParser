package com.aifp.aiagent.assistant;

import com.aifp.aiagent.common.ResultCode;
import com.aifp.aiagent.exception.BusinessException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 用户问题意图识别器（需求 §十四/§二十八第一跳，用户确认方案 A）
 * <p>
 * 第一次 LLM 调用：仅接收 projectName + 用户问题（追加约束 4，严禁注入
 * 结构化事实或文档内容，保持轻量且避免数据提前进入不必要的 Prompt），
 * 输出严格单键 JSON {@code {"intent":"<枚举值>"}}。
 * <p>
 * 错误边界（追加约束 11）：
 * <ul>
 *   <li>ChatModel 调用异常 → {@link ResultCode#AI_INVOKE_ERROR 3001}（模型不可用时
 *       后续回答调用也必然失败，早失败）</li>
 *   <li>JSON 解析失败 / intent 非法 / 缺键 → 降级 {@link AssistantIntent#UNKNOWN}，
 *       不抛 3002（意图是软分类，回答链路不能因此中断）</li>
 * </ul>
 *
 * @author Tang_tzb
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class QueryIntentAnalyzer {

    private final ChatModel chatModel;
    private final ObjectMapper objectMapper;

    /**
     * 识别用户问题意图（永不返回 null）。
     *
     * @param question    用户问题原文
     * @param projectName 项目名称（仅提供消歧语境，防"当前项目"被误判为跨项目比较，追加约束 13）
     * @return 识别结果；输出非法时降级 {@link AssistantIntent#UNKNOWN}
     */
    public AssistantIntent analyze(String question, String projectName) {
        String text = invokeModel(question, projectName);
        return parseIntent(text);
    }

    // ==================== 内部方法 ====================

    /**
     * 调用 ChatModel 执行意图分类；模型异常统一转 3001（追加约束 11）。
     */
    private String invokeModel(String question, String projectName) {
        try {
            Prompt prompt = new Prompt(List.of(
                    new SystemMessage(buildSystemPrompt()),
                    new UserMessage(buildUserPrompt(question, projectName))));
            ChatResponse response = chatModel.call(prompt);
            return response.getResult().getOutput().getText();
        } catch (Exception e) {
            log.error("意图识别 AI 模型调用失败: {}", e.getMessage(), e);
            throw new BusinessException(ResultCode.AI_INVOKE_ERROR, "AI 意图识别调用失败");
        }
    }

    /**
     * 构建意图分类 System Prompt：六个意图的判定标准由枚举 description 拼入
     * （单一事实来源），输出契约为严格单键 JSON。
     */
    private String buildSystemPrompt() {
        StringBuilder sb = new StringBuilder();
        sb.append("你是项目问答系统的意图分类器。请将用户问题归入以下意图之一：\n");
        for (AssistantIntent intent : AssistantIntent.values()) {
            sb.append("- ").append(intent.name()).append("：").append(intent.getDescription()).append('\n');
        }
        sb.append("""
                分类要求：
                1. 只依据问题本身的语义分类，不要回答问题，不要编造任何项目事实；
                2. 与当前项目明显无关或无法明确归类的问题，一律归入 UNKNOWN；
                3. 仅输出如下格式的 JSON，禁止输出 JSON 以外的任何内容：
                {"intent":"<意图枚举值>"}""");
        return sb.toString();
    }

    /**
     * 构建意图分类 User Prompt：仅项目名 + 用户问题两行（追加约束 4）。
     */
    private String buildUserPrompt(String question, String projectName) {
        return "项目名称：" + projectName + "\n用户问题：" + question;
    }

    /**
     * 解析意图输出：剥代码围栏后读取 intent 键；解析失败/intent 非法/缺键
     * 一律降级 UNKNOWN（追加约束 11，不抛 3002 不阻断回答链路）。
     */
    private AssistantIntent parseIntent(String text) {
        try {
            String cleaned = stripCodeFence(text);
            JsonNode node = objectMapper.readTree(cleaned);
            AssistantIntent intent = AssistantIntent.parse(node.path("intent").asText(null));
            if (intent != null) {
                return intent;
            }
            log.warn("意图识别输出非法 intent 值，降级 UNKNOWN: raw={}", text);
        } catch (Exception e) {
            log.warn("意图识别输出 JSON 解析失败，降级 UNKNOWN: raw={}", text);
        }
        return AssistantIntent.UNKNOWN;
    }

    /**
     * 剥离 ```json ... ``` 围栏（与 FieldExtractorServiceImpl 同款实现，修改需同步）。
     */
    private String stripCodeFence(String text) {
        String t = text == null ? "" : text.trim();
        if (t.startsWith("```")) {
            int firstNewline = t.indexOf('\n');
            if (firstNewline > 0) {
                t = t.substring(firstNewline + 1);
            }
            int lastFence = t.lastIndexOf("```");
            if (lastFence >= 0) {
                t = t.substring(0, lastFence);
            }
            t = t.trim();
        }
        return t;
    }
}
