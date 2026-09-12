package com.aifp.aiagent.assistant;

import com.aifp.aiagent.dto.AssistantReferenceVO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 回答引用标记解析器（Phase 11）：从 LLM 回答中确定性提取 [S{n}]/[D{n}] 引用
 * 标记，映射回本次 {@link AssistantPromptBuilder.PromptBuildResult#evidence()} 中
 * 的证据项，生成 citations（LLM 实际引用子集，按标记首次出现顺序去重）。
 * <p>
 * 硬边界（Phase 11 约束）：
 * <ul>
 *   <li>citations 只能来自 evidence——未知标记（如 LLM 编造的 [S99]）仅 warn
 *       并忽略，禁止创建新的 AssistantReferenceVO 或修改 references（约束 2）；</li>
 *   <li>解析只降级不报错——任何解析异常降级为部分 citations，不影响已成功生成
 *       的 answer（错误边界仍在 ChatModel 调用失败/空白，约束 3）；</li>
 *   <li>纯确定性解析，零 LLM 调用；仅解析本轮 answer 文本，不涉及对话历史中
 *       的旧标记（约束 4）；</li>
 *   <li>citations 只是"回答使用了哪些证据"的可追溯关系，不替代后端事实校验
 *       （约束 11）。</li>
 * </ul>
 *
 * @author Tang_tzb
 */
@Slf4j
@Component
public class AnswerCitationParser {

    /**
     * 引用标记模式：仅识别 [S数字] / [D数字]（大写字母 + 纯数字 + 紧贴右边界']'），
     * [S1a]、[s1]、[C1]、[S 1] 等一律不匹配；标记后紧跟文本（如 [S1]abc）不影响
     * 识别（约束 10：防止误匹配普通方括号内容）
     */
    private static final Pattern CITATION_PATTERN = Pattern.compile("\\[(S|D)(\\d+)]");

    /**
     * 解析回答中的引用标记为实际引用子集。
     *
     * @param answer   LLM 回答文本（null/blank 安全，返回空列表）
     * @param evidence 本次 PromptBuildResult.evidence（null/空安全，返回空列表）
     * @return 实际引用子集（evidence 成员本身，首次出现顺序去重；只降级不抛异常）
     */
    public List<AssistantReferenceVO> parse(String answer, List<AssistantReferenceVO> evidence) {
        List<AssistantReferenceVO> citations = new ArrayList<>();
        if (answer == null || answer.isBlank() || evidence == null || evidence.isEmpty()) {
            return citations;
        }
        Map<String, AssistantReferenceVO> evidenceByCitationId = indexEvidence(evidence);
        if (evidenceByCitationId.isEmpty()) {
            return citations;
        }
        // seen：同一标记多次出现按首次出现顺序去重（约束 9）
        Set<String> seen = new HashSet<>();
        try {
            Matcher matcher = CITATION_PATTERN.matcher(answer);
            while (matcher.find()) {
                String citationId = matcher.group(1) + matcher.group(2);
                if (!seen.add(citationId)) {
                    continue;
                }
                AssistantReferenceVO ref = evidenceByCitationId.get(citationId);
                if (ref == null) {
                    // LLM 编造/历史遗留的标记：只 warn 忽略，绝不生成新证据项（约束 2/4）
                    log.warn("回答中出现未知引用标记 [{}]（本轮 evidence 不存在），已忽略", citationId);
                    continue;
                }
                citations.add(ref);
            }
        } catch (Exception e) {
            // 解析异常降级为部分 citations，不影响已成功生成的 answer（约束 3）
            log.warn("回答引用解析异常，降级为部分 citations: {}", e.getMessage(), e);
        }
        return citations;
    }

    // ==================== 内部方法 ====================

    /**
     * 按 citationId 建立证据索引：citationId 缺失的证据项不参与映射（防御）；
     * putIfAbsent 兜底防重复登记（约束 1：一次构建内 citationId 应唯一）
     */
    private Map<String, AssistantReferenceVO> indexEvidence(List<AssistantReferenceVO> evidence) {
        Map<String, AssistantReferenceVO> index = new LinkedHashMap<>();
        for (AssistantReferenceVO ref : evidence) {
            if (ref != null && ref.getCitationId() != null && !ref.getCitationId().isBlank()) {
                index.putIfAbsent(ref.getCitationId(), ref);
            }
        }
        return index;
    }
}
