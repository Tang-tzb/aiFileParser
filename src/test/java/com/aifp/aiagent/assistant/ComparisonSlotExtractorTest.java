package com.aifp.aiagent.assistant;

import com.aifp.aiagent.common.ResultCode;
import com.aifp.aiagent.dto.ProjectFieldDictionaryVO;
import com.aifp.aiagent.exception.BusinessException;
import com.fasterxml.jackson.databind.ObjectMapper;
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

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link ComparisonSlotExtractor} 测试（离线，Phase 10）
 * <p>
 * 覆盖追加约束 2（全量语义槽位）、9（项目名仅候选，名称→ID 映射在后端）、
 * 12（fieldCode 不在字典/JSON 非法 → 空串确定性降级，不编造）；ChatModel 异常
 * → 3001（Phase 8 追加约束 11 延续）；代码围栏剥离与布尔缺省。
 *
 * @author Tang_tzb
 */
@ExtendWith(MockitoExtension.class)
class ComparisonSlotExtractorTest {

    private static final String QUESTION = "当前项目和项目B的总投资比较，谁更高？";

    @Mock
    private ChatModel chatModel;

    private ComparisonSlotExtractor extractor;

    @BeforeEach
    void setUp() {
        extractor = new ComparisonSlotExtractor(chatModel, new ObjectMapper());
    }

    /**
     * 正常解析：五槽位全量透出；fieldCode 已在字典内
     */
    @Test
    void extract_validJson_parsesAllSlots() {
        stubModel("""
                {"fieldCode":"total_investment","projects":["项目B"],"allProjects":false,
                 "referencesCurrent":true,"numericOperation":true}""");

        ComparisonSlotExtractor.ComparisonSlots slots = extractor.extract(
                QUESTION, dictionary(), List.of("示范项目", "项目B"));

        assertThat(slots.fieldCode()).isEqualTo("total_investment");
        assertThat(slots.projectNames()).containsExactly("项目B");
        assertThat(slots.allProjects()).isFalse();
        assertThat(slots.referencesCurrent()).isTrue();
        assertThat(slots.numericOperation()).isTrue();
    }

    /**
     * 全量语义（追加约束 2）：allProjects=true 且 projects 空——空数组≠全量由
     * Prompt 契约保证，解析层透传
     */
    @Test
    void extract_allProjectsSemantic_parsedAsTrue() {
        stubModel("""
                {"fieldCode":"total_investment","projects":[],"allProjects":true,
                 "referencesCurrent":false,"numericOperation":true}""");

        ComparisonSlotExtractor.ComparisonSlots slots = extractor.extract(
                "所有项目的总投资排名", dictionary(), List.of("示范项目", "项目B"));

        assertThat(slots.allProjects()).isTrue();
        assertThat(slots.projectNames()).isEmpty();
        assertThat(slots.numericOperation()).isTrue();
    }

    /**
     * fieldCode 不在字典内（编造/越权字段）→ 空串降级（追加约束 12），
     * 其余槽位照常解析
     */
    @Test
    void extract_fieldCodeNotInDictionary_degradesToEmpty() {
        stubModel("""
                {"fieldCode":"secret_field","projects":["项目B"],"allProjects":false,
                 "referencesCurrent":false,"numericOperation":false}""");

        ComparisonSlotExtractor.ComparisonSlots slots = extractor.extract(
                QUESTION, dictionary(), List.of("示范项目", "项目B"));

        assertThat(slots.fieldCode()).isEmpty();
        assertThat(slots.projectNames()).containsExactly("项目B");
    }

    /**
     * JSON 非法/输出非 JSON（如自然语言回答）→ 全缺省 slots（fieldCode 空串），
     * 组件内不抛业务异常，交由编排层确定性降级
     */
    @Test
    void extract_invalidJson_degradesToEmptySlots() {
        stubModel("这个问题无法确定要比较的字段。");

        ComparisonSlotExtractor.ComparisonSlots slots = extractor.extract(
                QUESTION, dictionary(), List.of("示范项目", "项目B"));

        assertThat(slots.fieldCode()).isEmpty();
        assertThat(slots.projectNames()).isEmpty();
        assertThat(slots.allProjects()).isFalse();
        assertThat(slots.referencesCurrent()).isFalse();
        assertThat(slots.numericOperation()).isFalse();
    }

    /**
     * 代码围栏（```json ... ```）剥离后正常解析（与 QueryIntentAnalyzer 同款容错）
     */
    @Test
    void extract_codeFencedJson_strippedAndParsed() {
        stubModel("""
                ```json
                {"fieldCode":"total_investment","projects":["项目A","项目B"],
                 "allProjects":false,"referencesCurrent":false,"numericOperation":true}
                ```""");

        ComparisonSlotExtractor.ComparisonSlots slots = extractor.extract(
                QUESTION, dictionary(), List.of("示范项目", "项目A", "项目B"));

        assertThat(slots.fieldCode()).isEqualTo("total_investment");
        assertThat(slots.projectNames()).containsExactly("项目A", "项目B");
    }

    /**
     * projects 元素去空白去重保序；布尔键缺失按 false（追加约束 9：名称仅候选原样透传）
     */
    @Test
    void extract_projectNamesTrimmedDeduped_booleansDefaultFalse() {
        stubModel("""
                {"fieldCode":"total_investment","projects":[" 项目B ","项目B","","项目A"]}""");

        ComparisonSlotExtractor.ComparisonSlots slots = extractor.extract(
                QUESTION, dictionary(), List.of("示范项目", "项目A", "项目B"));

        assertThat(slots.projectNames()).containsExactly("项目B", "项目A");
        assertThat(slots.allProjects()).isFalse();
        assertThat(slots.referencesCurrent()).isFalse();
        assertThat(slots.numericOperation()).isFalse();
    }

    /**
     * ChatModel 异常 → 3001 上抛（不吞异常、不在组件层降级）
     */
    @Test
    void extract_modelFailure_throwsAiInvokeError3001() {
        when(chatModel.call(any(Prompt.class))).thenThrow(new RuntimeException("timeout"));

        assertThatThrownBy(() -> extractor.extract(QUESTION, dictionary(), List.of("示范项目")))
                .isInstanceOfSatisfying(BusinessException.class, e ->
                        assertThat(e.getCode()).isEqualTo(ResultCode.AI_INVOKE_ERROR.getCode()));
    }

    /**
     * User Prompt 注入完整字段字典（fieldCode=fieldName 类型）与项目名称清单
     * （追加约束 9：候选来自后端集合投影）与用户问题
     */
    @Test
    void extract_promptContainsDictionaryProjectNamesAndQuestion() {
        stubModel("""
                {"fieldCode":"total_investment","projects":[],"allProjects":false,
                 "referencesCurrent":false,"numericOperation":false}""");

        extractor.extract(QUESTION, dictionary(), List.of("示范项目", "项目B"));

        ArgumentCaptor<Prompt> captor = ArgumentCaptor.forClass(Prompt.class);
        verify(chatModel).call(captor.capture());
        String userText = captor.getValue().getInstructions().stream()
                .filter(UserMessage.class::isInstance)
                .map(UserMessage.class::cast)
                .map(UserMessage::getText)
                .findFirst()
                .orElse("");
        assertThat(userText)
                .contains("【字段字典】")
                .contains("- total_investment=总投资金额（DECIMAL）")
                .contains("- building_area=建筑面积（DECIMAL）")
                .contains("【项目名称清单】")
                .contains("- 示范项目")
                .contains("- 项目B")
                .contains("用户问题：" + QUESTION);
    }

    // ==================== 测试辅助 ====================

    /**
     * 字段字典样本（DECIMAL 总投资 + DECIMAL 建筑面积）
     */
    private List<ProjectFieldDictionaryVO> dictionary() {
        return List.of(
                new ProjectFieldDictionaryVO("total_investment", "总投资金额", "DECIMAL"),
                new ProjectFieldDictionaryVO("building_area", "建筑面积", "DECIMAL"));
    }

    private void stubModel(String text) {
        when(chatModel.call(any(Prompt.class)))
                .thenReturn(new ChatResponse(List.of(new Generation(new AssistantMessage(text)))));
    }
}
