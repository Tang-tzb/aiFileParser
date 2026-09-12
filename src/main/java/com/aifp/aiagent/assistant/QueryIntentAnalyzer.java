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
 * 用户问题意图识别器（需求 §十四/§二十八第一跳；Phase 9 契约升级为意图+改写二合一）
 * <p>
 * 第一次 LLM 调用：接收 projectName + 最近 N 轮<b>用户问题</b>（追加约束 5，仅注入
 * 历史问题文本用于指代消解——严禁注入助手答案/结构化事实/文档内容）+ 当前用户问题，
 * 输出严格双键 JSON {@code {"intent":"<枚举值>","question":"<改写后独立问题>"}}。
 * 改写仅做指代消解（"那面积呢？"→"这个项目的建筑面积是多少？"），
 * 禁止编造或扩展问题语义（Phase 9 追加约束 2：standaloneQuestion 是本轮唯一有效问题）。
 * <p>
 * 错误边界（Phase 8 追加约束 11 延续）：
 * <ul>
 *   <li>ChatModel 调用异常 → {@link ResultCode#AI_INVOKE_ERROR 3001}（早失败）</li>
 *   <li>JSON 解析失败 / intent 非法 / 缺键 → 降级 {@link AssistantIntent#UNKNOWN} +
 *       原始问题兜底（不抛 3002）；question 缺失 → 原始问题兜底，intent 照常生效</li>
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
     * 识别用户问题意图并消解指代（永不返回 null）。
     *
     * @param question        用户问题原文
     * @param projectName     项目名称（仅提供消歧语境，防"当前项目"被误判为跨项目比较）
     * @param recentQuestions 最近 N 轮用户问题（时间顺序，最近一轮最后；可为空=首轮无历史）
     * @return 识别结果；输出非法时降级 UNKNOWN + 原始问题兜底
     */
    public AssistantIntentAnalysis analyze(String question, String projectName, List<String> recentQuestions) {
        String text = invokeModel(question, projectName, recentQuestions);
        return parseAnalysis(text, question);
    }

    // ==================== 内部方法 ====================

    /**
     * 调用 ChatModel 执行意图分类与改写；模型异常统一转 3001（追加约束 11）。
     */
    private String invokeModel(String question, String projectName, List<String> recentQuestions) {
        try {
            Prompt prompt = new Prompt(List.of(
                    new SystemMessage(buildSystemPrompt()),
                    new UserMessage(buildUserPrompt(question, projectName, recentQuestions))));
            ChatResponse response = chatModel.call(prompt);
            return response.getResult().getOutput().getText();
        } catch (Exception e) {
            log.error("意图识别 AI 模型调用失败: {}", e.getMessage(), e);
            throw new BusinessException(ResultCode.AI_INVOKE_ERROR, "AI 意图识别调用失败");
        }
    }

    /**
     * 构建意图分类 System Prompt：六个意图的判定标准由枚举 description 拼入
     * （单一事实来源），输出契约为严格双键 JSON + 指代消解改写规则。
     */
    private String buildSystemPrompt() {
        StringBuilder sb = new StringBuilder();
        sb.append("你是项目问答系统的意图分类器。请将用户问题归入以下意图之一：\n");
        for (AssistantIntent intent : AssistantIntent.values()) {
            sb.append("- ").append(intent.name()).append("：").append(intent.getDescription()).append('\n');
        }
        sb.append("""
                分类与改写要求：
                1. 只依据问题本身的语义分类，不要回答问题，不要编造任何项目事实；
                2. 与当前项目明显无关或无法明确归类的问题，一律归入 UNKNOWN；
                3. question 为结合对话历史改写后的独立问题：仅解析指代（如"那面积呢？"
                改写为"这个项目的建筑面积是多少？"），禁止编造或扩展问题内容；
                问题已独立或无对话历史时，question 必须原样返回用户问题；
                4. 仅输出如下格式的 JSON，禁止输出 JSON 以外的任何内容：
                {"intent":"<意图枚举值>","question":"<改写后的独立问题>"}""");
        return sb.toString();
    }

    /**
     * 构建意图分类 User Prompt：项目名 + （可选）历史用户问题段 + 用户问题。
     * 历史段仅注入问题文本（追加约束 5：保持轻量且不泄露答案/事实/文档内容）。
     */
    private String buildUserPrompt(String question, String projectName, List<String> recentQuestions) {
        StringBuilder sb = new StringBuilder();
        sb.append("项目名称：").append(projectName).append('\n');
        if (recentQuestions != null && !recentQuestions.isEmpty()) {
            sb.append("对话历史中的用户问题（仅用于理解当前问题指代）：\n");
            for (String prior : recentQuestions) {
                sb.append("- ").append(prior).append('\n');
            }
        }
        sb.append("用户问题：").append(question);
        return sb.toString();
    }

    /**
     * 解析意图输出：剥代码围栏后读取 intent/question 双键。
     * 解析失败/intent 非法/缺键 → UNKNOWN + 原始问题兜底（追加约束 11，
     * 不抛 3002 不阻断回答链路）；question 缺失 → 原始问题兜底，intent 照常生效。
     */
    private AssistantIntentAnalysis parseAnalysis(String text, String originalQuestion) {
        try {
            String cleaned = stripCodeFence(text);
            JsonNode node = objectMapper.readTree(cleaned);
            AssistantIntent intent = AssistantIntent.parse(node.path("intent").asText(null));
            if (intent == null) {
                log.warn("意图识别输出非法 intent 值，降级 UNKNOWN: raw={}", text);
                return AssistantIntentAnalysis.fallback(originalQuestion);
            }
            return new AssistantIntentAnalysis(intent, resolveStandaloneQuestion(node, originalQuestion, text));
        } catch (Exception e) {
            log.warn("意图识别输出 JSON 解析失败，降级 UNKNOWN: raw={}", text);
            return AssistantIntentAnalysis.fallback(originalQuestion);
        }
    }

    /**
     * 提取改写后独立问题：question 缺失/空白/非法时兜底原始问题（不编造改写）。
     */
    private String resolveStandaloneQuestion(JsonNode node, String originalQuestion, String rawText) {
        String rewritten = node.path("question").asText(null);
        if (rewritten == null || rewritten.isBlank()) {
            log.warn("意图识别输出 question 缺失，兜底原始问题: raw={}", rawText);
            return originalQuestion;
        }
        return rewritten.trim();
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
