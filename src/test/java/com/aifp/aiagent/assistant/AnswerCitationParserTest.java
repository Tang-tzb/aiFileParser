package com.aifp.aiagent.assistant;

import com.aifp.aiagent.dto.AssistantReferenceVO;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link AnswerCitationParser} 测试（离线纯单测，Phase 11 T3）
 * <p>
 * 覆盖：S/D 混合解析按首次出现顺序去重（结果为 evidence 成员本身）；未知标记
 * 仅忽略不创建新证据项（约束 2）；无标记/null/blank/空 evidence → 空列表；
 * 防误匹配（约束 10：小写/带字母后缀/带空格/[C1]/普通方括号均不识别）；
 * citationId 缺失的证据项不参与映射。
 *
 * @author Tang_tzb
 */
class AnswerCitationParserTest {

    private final AnswerCitationParser parser = new AnswerCitationParser();

    /**
     * S/D 混合解析：按首次出现顺序映射，重复标记去重，结果为 evidence 成员本身
     */
    @Test
    void parse_mixedMarkers_orderedDeduplicated_sameInstances() {
        AssistantReferenceVO s1 = ref("S1");
        AssistantReferenceVO s2 = ref("S2");
        AssistantReferenceVO d1 = ref("D1");
        List<AssistantReferenceVO> evidence = List.of(s1, s2, d1);

        List<AssistantReferenceVO> citations = parser.parse(
                "总投资为100万元[S1]，文档另有记载[D1]；另一来源记载[S2]；重复引用[S1]。",
                evidence);

        assertThat(citations).containsExactly(s1, d1, s2);
    }

    /**
     * 未知标记（编造的 [S99]/历史遗留 [D7]）：warn 忽略，不创建新 VO、
     * 不影响其余标记解析（约束 2/4）
     */
    @Test
    void parse_unknownMarkerIgnored() {
        AssistantReferenceVO s1 = ref("S1");
        List<AssistantReferenceVO> citations = parser.parse(
                "结论[S1]；编造[S99]；历史遗留[D7]。", List.of(s1));

        assertThat(citations).containsExactly(s1);
    }

    /**
     * 无标记 / null / blank answer / 空 evidence / null evidence → 空列表
     */
    @Test
    void parse_noMarkersOrEmptyInputs_emptyList() {
        assertThat(parser.parse("没有任何标记的回答。", List.of(ref("S1")))).isEmpty();
        assertThat(parser.parse(null, List.of(ref("S1")))).isEmpty();
        assertThat(parser.parse("   ", List.of(ref("S1")))).isEmpty();
        assertThat(parser.parse("带[S1]标记", List.of())).isEmpty();
        assertThat(parser.parse("带[S1]标记", null)).isEmpty();
    }

    /**
     * 防误匹配（约束 10）：仅 [S数字]/[D数字] 识别——小写、字母后缀、内部空格、
     * [C1] 抽取风格、普通方括号文本一律不匹配；标记后紧跟文本（[S1]abc）正常识别
     */
    @Test
    void parse_rejectsMalformedOrForeignMarkers() {
        AssistantReferenceVO s1 = ref("S1");
        List<AssistantReferenceVO> citations = parser.parse(
                "[s1]小写 [S1a]带后缀 [S 1]带空格 [C1]抽取风格 [备注]普通方括号 [S1]紧跟文本。",
                List.of(s1));

        assertThat(citations).containsExactly(s1);
    }

    /**
     * citationId 缺失的证据项不参与映射（防御；约束 1：一次构建内应全部有编号）
     */
    @Test
    void parse_evidenceWithoutCitationId_notMapped() {
        AssistantReferenceVO noId = new AssistantReferenceVO();
        noId.setType(AssistantReferenceVO.TYPE_STRUCTURED);
        List<AssistantReferenceVO> citations = parser.parse("回答[S1]。", List.of(noId));

        assertThat(citations).isEmpty();
    }

    /**
     * 测试辅助：构造带 citationId 的证据项
     */
    private AssistantReferenceVO ref(String citationId) {
        AssistantReferenceVO ref = new AssistantReferenceVO();
        ref.setType(citationId.startsWith("S")
                ? AssistantReferenceVO.TYPE_STRUCTURED : AssistantReferenceVO.TYPE_FILE);
        ref.setCitationId(citationId);
        return ref;
    }
}
