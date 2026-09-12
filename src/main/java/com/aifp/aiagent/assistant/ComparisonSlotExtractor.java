package com.aifp.aiagent.assistant;

import com.aifp.aiagent.common.ResultCode;
import com.aifp.aiagent.dto.ProjectFieldDictionaryVO;
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

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 跨项目比较槽位抽取器（Phase 10 Comparison / Ranking / Aggregation 第二跳）
 * <p>
 * 第二次轻量 LLM 调用：意图命中 COMPARISON 后，结合字段字典（fieldCode 快照）
 * 与可访问项目名清单，从（已消解指代的）standaloneQuestion 中抽取比较槽位。
 * 仅做"名称→语义键"的映射，<b>禁止计算任何数字</b>（追加约束 6）。
 * <p>
 * 槽位语义（追加约束 1/2/3）：
 * <ul>
 *   <li>{@code fieldCode}：只能取自字典，无法确定输出空串（编排层确定性降级）</li>
 *   <li>{@code allProjects}：仅当问题明确"所有项目/其他项目/全部项目/哪些项目"
 *       全量语义时为 true，此时 projects 必须为空——空数组≠全量（追加约束 2）</li>
 *   <li>{@code referencesCurrent}：问题含"当前项目/本项目"比较语义时为 true
 *       （编排层据此并入 currentProjectId，追加约束 1）</li>
 *   <li>{@code numericOperation}：问题要求排名/求和/平均/最大/最小/差值/计数/
 *       超过·低于筛选等数字计算诉求时为 true（类型不可计算时编排层确定性降级，
 *       追加约束 3）</li>
 * </ul>
 * <p>
 * 错误边界（Phase 8 追加约束 11 延续）：ChatModel 异常 → 3001；JSON 非法 →
 * fieldCode 空串 slots（编排层确定性降级，不在组件内抛业务异常）。
 *
 * @author Tang_tzb
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ComparisonSlotExtractor {

    private final ChatModel chatModel;
    private final ObjectMapper objectMapper;

    /**
     * 抽取比较槽位（永不返回 null；fieldCode 空串表示无法确定）。
     *
     * @param question     用户问题（standaloneQuestion，指代已消解）
     * @param dictionary   可访问项目集合内字段字典（fieldCode 快照去重）
     * @param projectNames 可访问项目名称清单（后端集合投影，追加约束 9）
     * @return 槽位结果；输出非法时 fieldCode="" 兜底
     */
    public ComparisonSlots extract(String question, List<ProjectFieldDictionaryVO> dictionary,
                                   List<String> projectNames) {
        String text = invokeModel(question, dictionary, projectNames);
        return parseSlots(text, dictionary);
    }

    /**
     * 调用 ChatModel 执行槽位抽取；模型异常统一转 3001（Phase 8 追加约束 11 延续）。
     */
    private String invokeModel(String question, List<ProjectFieldDictionaryVO> dictionary,
                               List<String> projectNames) {
        try {
            Prompt prompt = new Prompt(List.of(
                    new SystemMessage(buildSystemPrompt()),
                    new UserMessage(buildUserPrompt(question, dictionary, projectNames))));
            ChatResponse response = chatModel.call(prompt);
            return response.getResult().getOutput().getText();
        } catch (Exception e) {
            log.error("比较槽位抽取 AI 模型调用失败: {}", e.getMessage(), e);
            throw new BusinessException(ResultCode.AI_INVOKE_ERROR, "AI 槽位抽取调用失败");
        }
    }

    // ==================== 内部方法 ====================

    /**
     * 构建槽位抽取 System Prompt：字典成员约束 + 全量/当前项目/数字诉求判定规则
     * + 严格 JSON 输出契约。
     */
    private String buildSystemPrompt() {
        return """
                你是跨项目比较问答系统的槽位抽取器。请从用户问题中抽取以下槽位，规则：
                1. fieldCode：只能从【字段字典】给出的 fieldCode 中选择（依据 fieldName 语义匹配）；
                无法确定时输出空串 ""，禁止编造字典中不存在的 fieldCode；
                2. projects：用户问题中明确点名的项目名称数组，只能来自【项目名称清单】，
                禁止编造清单中不存在的名称；问题未点名任何项目时输出空数组 []；
                3. allProjects：仅当问题表达"所有项目/全部项目/其他项目/哪些项目"等全量
                语义时为 true，且此时 projects 必须为空数组；否则为 false；
                4. referencesCurrent：问题提到"当前项目/本项目"时为 true（无需写入 projects），否则 false；
                5. numericOperation：问题要求排名、第几、求和、平均、最大、最小、差值、
                数量统计或"超过/低于"类数值筛选时为 true，仅询问数值大小或字段值时为 false；
                6. 不要回答问题，不要计算任何数字，禁止输出 JSON 以外的任何内容，格式：
                {"fieldCode":"<字典中的fieldCode或空串>","projects":["<项目名称>"],"allProjects":false,"referencesCurrent":false,"numericOperation":false}""";
    }

    /**
     * 构建槽位抽取 User Prompt：字段字典 + 项目名称清单 + 用户问题（standaloneQuestion）。
     */
    private String buildUserPrompt(String question, List<ProjectFieldDictionaryVO> dictionary,
                                   List<String> projectNames) {
        StringBuilder sb = new StringBuilder();
        sb.append("【字段字典】（fieldCode=fieldName，类型）：\n");
        if (dictionary == null || dictionary.isEmpty()) {
            sb.append("(空)\n");
        } else {
            for (ProjectFieldDictionaryVO item : dictionary) {
                sb.append("- ").append(item.getFieldCode()).append('=')
                        .append(item.getFieldName()).append("（").append(item.getFieldType()).append("）\n");
            }
        }
        sb.append("【项目名称清单】：\n");
        if (projectNames == null || projectNames.isEmpty()) {
            sb.append("(空)\n");
        } else {
            for (String name : projectNames) {
                sb.append("- ").append(name).append('\n');
            }
        }
        sb.append("用户问题：").append(question);
        return sb.toString();
    }

    /**
     * 解析槽位输出：剥代码围栏后读取五键；fieldCode 必须在字典内（不在 → 空串降级，
     * 追加约束 9/12）；projects 去空白去重保序；布尔键缺失按 false。
     */
    private ComparisonSlots parseSlots(String text, List<ProjectFieldDictionaryVO> dictionary) {
        try {
            String cleaned = stripCodeFence(text);
            JsonNode node = objectMapper.readTree(cleaned);
            String fieldCode = resolveFieldCode(node, dictionary, text);
            return new ComparisonSlots(fieldCode, resolveProjectNames(node),
                    node.path("allProjects").asBoolean(false),
                    node.path("referencesCurrent").asBoolean(false),
                    node.path("numericOperation").asBoolean(false));
        } catch (Exception e) {
            log.warn("比较槽位输出 JSON 解析失败，降级 fieldCode=\"\": raw={}", text);
            return new ComparisonSlots("", List.of(), false, false, false);
        }
    }

    /**
     * fieldCode 成员校验：字典缺失/空白/不在字典内 → 空串（编排层确定性降级，
     * 追加约束 12：无法确定字段不强行计算）。
     */
    private String resolveFieldCode(JsonNode node, List<ProjectFieldDictionaryVO> dictionary,
                                    String rawText) {
        String fieldCode = node.path("fieldCode").asText(null);
        boolean inDictionary = fieldCode != null && dictionary != null && dictionary.stream()
                .anyMatch(item -> item.getFieldCode().equals(fieldCode));
        if (!inDictionary) {
            log.warn("比较槽位 fieldCode 非法（不在字典内），降级空串: raw={}", rawText);
            return "";
        }
        return fieldCode.trim();
    }

    /**
     * 项目名称候选整理：去空白、去重保序（名称→ID 映射与多命中判定在后端编排层）
     */
    private List<String> resolveProjectNames(JsonNode node) {
        Set<String> names = new LinkedHashSet<>();
        node.path("projects").forEach(item -> {
            String name = item.asText(null);
            if (name != null && !name.isBlank()) {
                names.add(name.trim());
            }
        });
        return List.copyOf(names);
    }

    /**
     * 剥离 ```json ... ``` 围栏（与 QueryIntentAnalyzer 同款实现，修改需同步）。
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

    /**
     * 槽位抽取结果（fieldCode 空串 = 无法确定，编排层确定性降级）
     *
     * @param fieldCode         字段编码（""=未确定；已按字典成员校验）
     * @param projectNames      用户点名的项目名称候选（原样透传，名称→ID 映射在后端，
     *                          追加约束 9；allProjects=true 时为空）
     * @param allProjects       是否全量语义（所有/其他/全部/哪些项目）
     * @param referencesCurrent 是否引用当前项目（当前项目/本项目）
     * @param numericOperation  是否要求数字计算（排名/聚合/差值/数值筛选）
     */
    public record ComparisonSlots(String fieldCode, List<String> projectNames,
                                  boolean allProjects, boolean referencesCurrent,
                                  boolean numericOperation) {
    }
}
