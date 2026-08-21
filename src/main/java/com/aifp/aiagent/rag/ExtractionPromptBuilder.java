package com.aifp.aiagent.rag;

import com.aifp.aiagent.dto.FieldError;
import com.aifp.aiagent.dto.FormFieldVO;
import org.springframework.ai.document.Document;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 字段抽取 Prompt 动态构建器
 * <p>
 * 由 {@link FormFieldVO} 实时生成系统提示与 JSON Schema，字段变化无需改代码。
 * schema key = fieldCode，值含 {@code fieldType.jsonSchemaType} / required / 描述，
 * 类型映射复用 {@link com.aifp.aiagent.entity.enums.FieldType#getJsonSchemaType()}，无硬编码映射表。
 * Retry 时 {@link #buildRetryFeedback} 把字段错误反馈注入系统提示。
 *
 * @author Tang_tzb
 */
@Component
public class ExtractionPromptBuilder {

    private static final String SYSTEM_PROMPT_HEADER =
            "你是文档字段抽取助手。请根据提供的文档片段，抽取下列字段并严格以 JSON 对象返回，" +
                    "不要包含任何解释性文字或 markdown 围栏。字段 schema 如下（key 为字段编码，值为类型与说明）：\n";

    private static final String USER_PROMPT_HEADER = "文档片段：\n";

    private static final String USER_PROMPT_FOOTER =
            "\n\n请严格按上述 schema 输出一个 JSON 对象，缺失字段用 null，不要解释。";

    private static final String RETRY_FEEDBACK_HEADER =
            "\n\n上一次返回存在以下问题，请修正后重新返回完整 JSON：\n";

    /**
     * 构建系统提示（含动态 JSON schema，不带反馈）。
     */
    public String buildSystemPrompt(List<FormFieldVO> fields) {
        return buildSystemPrompt(fields, null);
    }

    /**
     * 构建系统提示（含动态 JSON schema + 可选 Retry 反馈）。
     *
     * @param fields   字段定义列表
     * @param feedback Retry 错误反馈，null 表示首次调用不带反馈
     * @return 系统提示文本
     */
    public String buildSystemPrompt(List<FormFieldVO> fields, String feedback) {
        String prompt = SYSTEM_PROMPT_HEADER + buildSchema(fields);
        if (feedback != null && !feedback.isBlank()) {
            prompt += RETRY_FEEDBACK_HEADER + feedback;
        }
        return prompt;
    }

    /**
     * 构建 Retry 错误反馈文本：列出每个失败字段的编码、错误类型与原始值。
     */
    public String buildRetryFeedback(List<FieldError> errors) {
        if (errors == null || errors.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < errors.size(); i++) {
            if (i > 0) {
                sb.append("\n");
            }
            FieldError e = errors.get(i);
            sb.append("- 字段 ").append(e.getFieldCode())
                    .append(" 错误类型[").append(e.getErrorType()).append("]: ")
                    .append(e.getMessage());
        }
        return sb.toString();
    }

    /**
     * 构建用户提示（拼接 chunks 内容）。
     *
     * @param chunks 检索命中的切片
     * @return 用户提示文本
     */
    public String buildUserPrompt(List<Document> chunks) {
        StringBuilder sb = new StringBuilder(USER_PROMPT_HEADER);
        for (int i = 0; i < chunks.size(); i++) {
            if (i > 0) {
                sb.append("\n---\n");
            }
            sb.append(chunks.get(i).getText());
        }
        sb.append(USER_PROMPT_FOOTER);
        return sb.toString();
    }

    /**
     * 构建动态 JSON schema 字符串。
     */
    private String buildSchema(List<FormFieldVO> fields) {
        StringBuilder schema = new StringBuilder("{");
        for (int i = 0; i < fields.size(); i++) {
            if (i > 0) {
                schema.append(",");
            }
            schema.append(buildFieldSchema(fields.get(i)));
        }
        schema.append("}");
        return schema.toString();
    }

    /**
     * 单字段 schema 片段：{"fieldCode":"type,必填?,fieldName,description?"}。
     */
    private String buildFieldSchema(FormFieldVO f) {
        StringBuilder val = new StringBuilder();
        val.append(f.getFieldType().getJsonSchemaType());
        if (Boolean.TRUE.equals(f.getRequired())) {
            val.append(",必填");
        }
        val.append(",").append(nullSafe(f.getFieldName()));
        String desc = f.getDescription();
        if (desc != null && !desc.isBlank()) {
            val.append(",").append(desc);
        }
        return "\"" + escape(f.getFieldCode()) + "\":\"" + escape(val.toString()) + "\"";
    }

    private String nullSafe(String s) {
        return s == null ? "" : s;
    }

    /**
     * 转义 JSON 字符串中的特殊字符，保证 schema 始终为合法 JSON。
     */
    private String escape(String s) {
        if (s == null) {
            return "";
        }
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
