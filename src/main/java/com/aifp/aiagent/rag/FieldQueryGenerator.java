package com.aifp.aiagent.rag;

import com.aifp.aiagent.dto.FormFieldVO;
import org.springframework.stereotype.Component;

/**
 * 按字段生成 Milvus 检索 query
 * <p>
 * 对应用户示例「字段：投资金额 → 请从文档中提取投资金额」。
 * 独立组件便于单测与未来替换检索策略。
 *
 * @author Tang_tzb
 */
@Component
public class FieldQueryGenerator {

    private static final String QUERY_TEMPLATE = "请从文档中提取%s。";

    /**
     * 根据字段定义生成检索 query：fieldName 为主，description 非空则作为检索提示追加。
     *
     * @param field 字段定义
     * @return 检索 query
     */
    public String generate(FormFieldVO field) {
        StringBuilder sb = new StringBuilder();
        sb.append(String.format(QUERY_TEMPLATE, field.getFieldName()));
        String desc = field.getDescription();
        if (desc != null && !desc.isBlank()) {
            sb.append(desc).append("。");
        }
        return sb.toString();
    }
}
