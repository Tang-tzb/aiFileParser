package com.aifp.aiagent.rag;

import com.aifp.aiagent.dto.FieldError;
import com.aifp.aiagent.dto.FormFieldVO;
import com.aifp.aiagent.entity.enums.FieldType;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link ExtractionPromptBuilder} 测试（确定性，纯字符串构建）。
 * <p>
 * 覆盖：Schema 构建含必填标记、Retry 反馈注入与格式化、空错误反馈边界。
 *
 * @author Tang_tzb
 */
class ExtractionPromptBuilderTest {

    private final ExtractionPromptBuilder builder = new ExtractionPromptBuilder();

    @Test
    void buildSystemPrompt_withoutFeedback_containsSchemaOnly() {
        String prompt = builder.buildSystemPrompt(List.of(
                field("projectName", FieldType.STRING, true, "项目名称")));

        assertThat(prompt).contains("projectName");
        assertThat(prompt).contains("必填");
        assertThat(prompt).doesNotContain("上一次返回存在以下问题");
    }

    @Test
    void buildSystemPrompt_withFeedback_appendsFeedback() {
        String feedback = "- 字段 amount 错误类型[TYPE]: 类型转换失败";
        String prompt = builder.buildSystemPrompt(
                List.of(field("amount", FieldType.DECIMAL, true, "投资金额")), feedback);

        assertThat(prompt).contains("amount");
        assertThat(prompt).contains("上一次返回存在以下问题");
        assertThat(prompt).contains("类型转换失败");
    }

    @Test
    void buildRetryFeedback_nullOrEmpty_returnsEmpty() {
        assertThat(builder.buildRetryFeedback(null)).isEmpty();
        assertThat(builder.buildRetryFeedback(List.of())).isEmpty();
    }

    @Test
    void buildRetryFeedback_errors_formattedWithFieldAndType() {
        List<FieldError> errors = List.of(
                new FieldError("amount", FieldSchemaValidator.ERR_TYPE, "类型转换失败: 500万元", "500万元"),
                new FieldError("signDate", FieldSchemaValidator.ERR_FORMAT, "日期格式错误: 无", "无"));

        String feedback = builder.buildRetryFeedback(errors);

        assertThat(feedback).contains("字段 amount");
        assertThat(feedback).contains("错误类型[TYPE]");
        assertThat(feedback).contains("字段 signDate");
        assertThat(feedback).contains("错误类型[FORMAT]");
    }

    // ==================== Phase 4：溯源引用（软依赖） ====================

    /**
     * 片段编号：每个片段前置 [C{n}] 编号行，n 按列表顺序从 1 递增（与
     * FieldExtractorServiceImpl.buildMarkerIndex 映射规则一致）。
     */
    @Test
    void buildUserPrompt_prefixesChunksWithCMarkers() {
        String prompt = builder.buildUserPrompt(List.of(
                new org.springframework.ai.document.Document("片段一内容"),
                new org.springframework.ai.document.Document("片段二内容")));

        assertThat(prompt).contains("[C1]\n片段一内容");
        assertThat(prompt).contains("[C2]\n片段二内容");
    }

    /**
     * sources 软依赖指令：footer 明确旁路键语义与"缺失不影响抽取"，
     * 不得将 sources 写成必返要求（补充约束 6）。
     */
    @Test
    void buildUserPrompt_mentionsSourcesAsOptionalSideChannel() {
        String prompt = builder.buildUserPrompt(
                List.of(new org.springframework.ai.document.Document("片段内容")));

        assertThat(prompt).contains("\"sources\"");
        assertThat(prompt).contains("C2");
        assertThat(prompt).contains("缺失不影响抽取结果本身");
    }

    // ==================== 测试数据 ====================

    private FormFieldVO field(String code, FieldType type, boolean required, String name) {
        FormFieldVO f = new FormFieldVO();
        f.setFieldCode(code);
        f.setFieldName(name);
        f.setFieldType(type);
        f.setRequired(required);
        return f;
    }
}
