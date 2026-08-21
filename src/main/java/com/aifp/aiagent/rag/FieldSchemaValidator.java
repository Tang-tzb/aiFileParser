package com.aifp.aiagent.rag;

import com.aifp.aiagent.dto.FieldError;
import com.aifp.aiagent.dto.FormFieldVO;
import com.aifp.aiagent.entity.enums.FieldType;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 字段抽取结果 Schema 校验 + 类型转换器
 * <p>
 * 编程式实现动态字段的 Bean Validation 语义（required/类型/格式），
 * 因表单字段运行时动态，不适用注解式 jakarta Bean Validation。
 * 对 INTEGER/DECIMAL 支持「万/亿」单位与千分位逗号智能解析；中文数字不在本阶段范围。
 *
 * @author Tang_tzb
 */
@Component
public class FieldSchemaValidator {

    /**
     * 错误类型常量
     */
    public static final String ERR_MISSING = "MISSING";
    public static final String ERR_TYPE = "TYPE";
    public static final String ERR_FORMAT = "FORMAT";

    private static final BigDecimal WAN = new BigDecimal("10000");
    private static final BigDecimal YI = new BigDecimal("100000000");

    /**
     * 常见中文文档日期格式，按优先级尝试
     */
    private static final DateTimeFormatter[] DATE_FORMATS = {
            DateTimeFormatter.ofPattern("yyyy-MM-dd"),
            DateTimeFormatter.ofPattern("yyyy/MM/dd"),
            DateTimeFormatter.ofPattern("yyyy年MM月dd日"),
            DateTimeFormatter.ofPattern("yyyyMMdd")
    };

    /**
     * 校验 + 类型转换。
     *
     * @param raw    AI 返回的原始 Map
     * @param fields 字段定义列表
     * @return 校验结果（类型化 coerced + 字段错误 errors）
     */
    public ValidationResult validate(Map<String, Object> raw, List<FormFieldVO> fields) {
        Map<String, Object> coerced = new LinkedHashMap<>();
        List<FieldError> errors = new ArrayList<>();
        for (FormFieldVO f : fields) {
            Object v = raw == null ? null : raw.get(f.getFieldCode());
            if (v == null) {
                handleMissing(f, coerced, errors);
                continue;
            }
            coerceSafely(f, v, coerced, errors);
        }
        return new ValidationResult(coerced, errors);
    }

    private void handleMissing(FormFieldVO f, Map<String, Object> coerced, List<FieldError> errors) {
        if (Boolean.TRUE.equals(f.getRequired())) {
            errors.add(new FieldError(f.getFieldCode(), ERR_MISSING, "必填字段缺失", null));
        }
        coerced.put(f.getFieldCode(), null);
    }

    /**
     * 转换单字段，失败归类为 TYPE / FORMAT 错误，coerced 置 null。
     */
    private void coerceSafely(FormFieldVO f, Object v, Map<String, Object> coerced, List<FieldError> errors) {
        try {
            coerced.put(f.getFieldCode(), coerce(f.getFieldType(), v));
        } catch (DateTimeParseException e) {
            errors.add(new FieldError(f.getFieldCode(), ERR_FORMAT, "日期格式错误: " + v, v));
            coerced.put(f.getFieldCode(), null);
        } catch (RuntimeException e) {
            errors.add(new FieldError(f.getFieldCode(), ERR_TYPE, "类型转换失败: " + v, v));
            coerced.put(f.getFieldCode(), null);
        }
    }

    /**
     * 按 FieldType 派发转换；不可转换抛 RuntimeException 子类。
     */
    private Object coerce(FieldType type, Object v) {
        return switch (type) {
            case STRING -> v.toString();
            case INTEGER -> coerceInteger(v);
            case DECIMAL -> coerceDecimal(v);
            case BOOLEAN -> coerceBoolean(v);
            case DATE -> coerceDate(v);
        };
    }

    private Long coerceInteger(Object v) {
        BigDecimal d = parseWithUnit(v);
        try {
            return d.longValueExact();
        } catch (ArithmeticException e) {
            throw new NumberFormatException("期望整数但值为小数: " + v);
        }
    }

    private BigDecimal coerceDecimal(Object v) {
        return parseWithUnit(v).stripTrailingZeros();
    }

    private Boolean coerceBoolean(Object v) {
        if (v instanceof Boolean b) {
            return b;
        }
        String s = v.toString().trim();
        if (s.equalsIgnoreCase("true") || "1".equals(s)) {
            return Boolean.TRUE;
        }
        if (s.equalsIgnoreCase("false") || "0".equals(s)) {
            return Boolean.FALSE;
        }
        throw new IllegalArgumentException("无法解析为布尔: " + v);
    }

    private LocalDate coerceDate(Object v) {
        if (v instanceof LocalDate d) {
            return d;
        }
        String s = v.toString().trim();
        for (DateTimeFormatter f : DATE_FORMATS) {
            try {
                return LocalDate.parse(s, f);
            } catch (DateTimeParseException ignored) {
                // 尝试下一种格式
            }
        }
        throw new DateTimeParseException("无法解析为日期: " + v, s, 0);
    }

    /**
     * 智能数字解析：Number 直转；字符串剥离尾部「万/亿」单位 ×倍率，去千分位逗号。
     */
    private BigDecimal parseWithUnit(Object v) {
        if (v instanceof Number n) {
            return new BigDecimal(n.toString());
        }
        String s = v.toString().trim();
        if (s.isEmpty()) {
            throw new NumberFormatException("空值无法解析为数字");
        }
        s = s.replace(",", "");
        BigDecimal multiplier = BigDecimal.ONE;
        if (s.endsWith("万")) {
            multiplier = WAN;
            s = s.substring(0, s.length() - 1).trim();
        } else if (s.endsWith("亿")) {
            multiplier = YI;
            s = s.substring(0, s.length() - 1).trim();
        }
        try {
            return new BigDecimal(s).multiply(multiplier);
        } catch (NumberFormatException e) {
            throw new NumberFormatException("无法解析为数字: " + v);
        }
    }

    /**
     * 校验结果（内部传递用），含类型化值与字段错误。
     */
    @Data
    @RequiredArgsConstructor
    public static class ValidationResult {
        private final Map<String, Object> coerced;
        private final List<FieldError> errors;

        public boolean hasErrors() {
            return errors != null && !errors.isEmpty();
        }
    }
}
