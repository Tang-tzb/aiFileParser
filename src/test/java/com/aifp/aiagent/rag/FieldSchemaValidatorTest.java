package com.aifp.aiagent.rag;

import com.aifp.aiagent.dto.FieldError;
import com.aifp.aiagent.dto.FormFieldVO;
import com.aifp.aiagent.entity.enums.FieldType;
import com.aifp.aiagent.rag.FieldSchemaValidator.ValidationResult;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link FieldSchemaValidator} 测试（确定性，纯内存计算）。
 * <p>
 * 覆盖：正常转换、必填缺失、可选缺失、类型错误、智能单位解析、日期多格式。
 *
 * @author Tang_tzb
 */
class FieldSchemaValidatorTest {

    private final FieldSchemaValidator validator = new FieldSchemaValidator();

    @Test
    void validate_allFieldsOk_returnsCoercedNoErrors() {
        List<FormFieldVO> fields = List.of(
                field("name", FieldType.STRING, true),
                field("amount", FieldType.DECIMAL, true),
                field("count", FieldType.INTEGER, false),
                field("active", FieldType.BOOLEAN, false),
                field("signDate", FieldType.DATE, false));
        Map<String, Object> raw = Map.of(
                "name", "智慧校园",
                "amount", 5000000,
                "count", 3,
                "active", "true",
                "signDate", "2026/03/05");

        ValidationResult vr = validator.validate(raw, fields);

        assertThat(vr.hasErrors()).isFalse();
        assertThat(vr.getCoerced()).containsEntry("name", "智慧校园");
        assertThat((BigDecimal) vr.getCoerced().get("amount")).isEqualByComparingTo(new BigDecimal("5000000"));
        assertThat(vr.getCoerced()).containsEntry("count", 3L);
        assertThat(vr.getCoerced()).containsEntry("active", Boolean.TRUE);
        assertThat(vr.getCoerced()).containsEntry("signDate", LocalDate.of(2026, 3, 5));
    }

    @Test
    void validate_missingRequired_addsMissingError() {
        FormFieldVO required = field("projectName", FieldType.STRING, true);
        ValidationResult vr = validator.validate(Map.of(), List.of(required));

        assertThat(vr.hasErrors()).isTrue();
        FieldError err = vr.getErrors().get(0);
        assertThat(err.getFieldCode()).isEqualTo("projectName");
        assertThat(err.getErrorType()).isEqualTo(FieldSchemaValidator.ERR_MISSING);
        assertThat(vr.getCoerced()).containsEntry("projectName", null);
    }

    @Test
    void validate_missingOptional_noErrorNullValue() {
        FormFieldVO optional = field("remark", FieldType.STRING, false);
        ValidationResult vr = validator.validate(Map.of(), List.of(optional));

        assertThat(vr.hasErrors()).isFalse();
        assertThat(vr.getCoerced()).containsEntry("remark", null);
    }

    @Test
    void validate_typeError_addsTypeError() {
        FormFieldVO f = field("count", FieldType.INTEGER, true);
        ValidationResult vr = validator.validate(Map.of("count", "金额待定"), List.of(f));

        assertThat(vr.hasErrors()).isTrue();
        FieldError err = vr.getErrors().get(0);
        assertThat(err.getFieldCode()).isEqualTo("count");
        assertThat(err.getErrorType()).isEqualTo(FieldSchemaValidator.ERR_TYPE);
        assertThat(err.getRawValue()).isEqualTo("金额待定");
    }

    @Test
    void validate_decimalWithWanUnit_parsedSuccessfully() {
        FormFieldVO f = field("amount", FieldType.DECIMAL, true);
        // 纯单位"万"（非"万元"）可被智能解析：500万 → 5000000
        ValidationResult vr = validator.validate(Map.of("amount", "500万"), List.of(f));

        assertThat(vr.hasErrors()).isFalse();
        assertThat((BigDecimal) vr.getCoerced().get("amount"))
                .isEqualByComparingTo(new BigDecimal("5000000"));
    }

    /**
     * 用户示例：amount="500万元"（含"元"）无法被单位解析 → TYPE 错误 → 触发 Retry。
     */
    @Test
    void validate_decimalWithYuanSuffix_addsTypeErrorForRetry() {
        FormFieldVO f = field("amount", FieldType.DECIMAL, true);
        ValidationResult vr = validator.validate(Map.of("amount", "500万元"), List.of(f));

        assertThat(vr.hasErrors()).isTrue();
        assertThat(vr.getErrors().get(0).getErrorType()).isEqualTo(FieldSchemaValidator.ERR_TYPE);
    }

    @Test
    void validate_integerWithYiUnit_parsedSuccessfully() {
        FormFieldVO f = field("total", FieldType.INTEGER, true);
        ValidationResult vr = validator.validate(Map.of("total", "1亿"), List.of(f));

        assertThat(vr.hasErrors()).isFalse();
        assertThat(vr.getCoerced()).containsEntry("total", 100000000L);
    }

    @Test
    void validate_dateFormatError_addsFormatError() {
        FormFieldVO f = field("signDate", FieldType.DATE, true);
        ValidationResult vr = validator.validate(Map.of("signDate", "2026年13月40日"), List.of(f));

        assertThat(vr.hasErrors()).isTrue();
        FieldError err = vr.getErrors().get(0);
        assertThat(err.getErrorType()).isEqualTo(FieldSchemaValidator.ERR_FORMAT);
    }

    @Test
    void validate_integerAsDecimal_addsTypeError() {
        FormFieldVO f = field("count", FieldType.INTEGER, true);
        ValidationResult vr = validator.validate(Map.of("count", 3.5), List.of(f));

        assertThat(vr.hasErrors()).isTrue();
        assertThat(vr.getErrors().get(0).getErrorType()).isEqualTo(FieldSchemaValidator.ERR_TYPE);
    }

    // ==================== 测试数据 ====================

    private FormFieldVO field(String code, FieldType type, boolean required) {
        FormFieldVO f = new FormFieldVO();
        f.setFieldCode(code);
        f.setFieldName(code);
        f.setFieldType(type);
        f.setRequired(required);
        return f;
    }
}
