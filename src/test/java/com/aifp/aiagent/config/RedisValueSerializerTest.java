package com.aifp.aiagent.config;

import com.aifp.aiagent.dto.ExtractionResult;
import com.aifp.aiagent.dto.FieldError;
import com.aifp.aiagent.dto.TaskProgress;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.serializer.RedisSerializer;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link RedisConfig#redisValueSerializer()} 序列化契约测试
 * <p>
 * 阶段 13 缺陷回归防线：TaskProgress.result → ExtractionResult.values 携带
 * FieldType.DATE 转换出的 {@link LocalDate}（声明类型 Object），旧无参
 * GenericJackson2JsonRedisSerializer 因缺 JSR310 模块在发布 100% 进度时抛
 * UnsupportedTypeSerializer。验证：① 全类型 round-trip 无损；
 * ② 日期输出为 ISO-8601 字符串（非时间戳数组）；③ {@code @class} 类型信息保留
 * 且 PTV 白名单拒绝越界多态类型。
 *
 * @author Tang_tzb
 */
class RedisValueSerializerTest {

    private static final Long FILE_ID = 2094345216147853314L;
    private static final Long FORM_ID = 2093973916833316866L;

    private RedisSerializer<Object> serializer;

    @BeforeEach
    void setUp() {
        serializer = RedisConfig.redisValueSerializer();
    }

    /**
     * round-trip：LocalDate/BigDecimal/Long/String/null 值与 FieldError.originalValue 全部无损还原。
     */
    @Test
    void serializeDeserialize_taskProgressWithTypedValues_roundTripLossless() {
        TaskProgress original = new TaskProgress("t-1", FILE_ID, FORM_ID,
                "SUCCESS", 100, "完成", buildExtractionResult());

        Object restored = serializer.deserialize(serializer.serialize(original));

        assertThat(restored).isInstanceOf(TaskProgress.class);
        assertThat(restored).isEqualTo(original);
    }

    /**
     * 日期格式防线：constructDate 输出 ISO 字符串（JSR310 生效、时间戳数组禁用）。
     */
    @Test
    void serialize_localDateValue_writesIsoString() {
        String json = new String(serializer.serialize(buildProgress()), StandardCharsets.UTF_8);

        assertThat(json).contains("java.time.LocalDate");
        assertThat(json).contains("\"2026-09-08\"");
        assertThat(json).doesNotContain("[2026,9,8]");
    }

    /**
     * 类型信息防线：{@code @class} 保留（NON_FINAL 多态）；
     * PTV 白名单（com.aifp.aiagent./java./org.springframework.）之外的类型
     * 在反序列化侧被拒绝（Jackson 仅在反序列化侧咨询 PTV，防 gadget 注入）。
     */
    @Test
    void polymorphicHandling_keepsClassInfoAndRejectsForeignTypes() {
        String json = new String(serializer.serialize(buildProgress()), StandardCharsets.UTF_8);
        assertThat(json).contains("\"@class\":\"com.aifp.aiagent.dto.TaskProgress\"");

        assertThatThrownBy(() -> serializer.deserialize(
                "{\"@class\":\"ch.qos.logback.classic.Logger\",\"name\":\"evil\"}"
                        .getBytes(StandardCharsets.UTF_8)))
                .isInstanceOf(RuntimeException.class);
    }

    /**
     * 构造 100% SUCCESS 终态进度（result 携带类型化抽取结果）。
     */
    private TaskProgress buildProgress() {
        return new TaskProgress("t-1", FILE_ID, FORM_ID, "SUCCESS", 100, "完成", buildExtractionResult());
    }

    /**
     * 类型化抽取结果：覆盖 values 中全部实际可能出现的值类型与字段错误。
     */
    private ExtractionResult buildExtractionResult() {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("constructDate", LocalDate.of(2026, 9, 8));
        values.put("amount", new BigDecimal("170649.08"));
        values.put("projectId", FILE_ID);
        values.put("planName", "职教园一期");
        values.put("remark", null);

        ExtractionResult result = new ExtractionResult();
        result.setValues(values);
        result.setErrors(List.of(new FieldError(
                "signDate", "FORMAT", "日期格式错误: 2026/13/01", LocalDate.of(2026, 1, 1))));
        result.setAttemptsUsed(2);
        return result;
    }
}
