package com.aifp.aiagent.entity.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;
import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 表单字段类型枚举
 * <p>
 * 设计要点：每个类型携带 {@code jsonSchemaType}，为后续阶段将字段定义转换为
 * JSON Schema / AI 抽取 Prompt 提供直接映射，无需再写映射表。
 *
 * @author aiFileParser
 */
@Getter
@AllArgsConstructor
public enum FieldType {

    STRING("STRING", "字符串", "string"),
    INTEGER("INTEGER", "整数", "integer"),
    DECIMAL("DECIMAL", "小数", "number"),
    DATE("DATE", "日期", "string"),
    BOOLEAN("BOOLEAN", "布尔", "boolean");

    /**
     * 持久化到 DB field_type 列的值
     */
    @EnumValue
    private final String code;

    /**
     * 中文展示标签
     */
    private final String label;

    /**
     * 对应 JSON Schema 的 type，供后续 AI Prompt 生成使用
     */
    private final String jsonSchemaType;
}
