package com.aifp.aiagent.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * 项目助手对话请求（需求 §十三）
 * <p>
 * Phase 8 会话无状态（用户确认 + 追加约束 8）：{@code conversationId} 仅透传
 * （空白时服务端生成），不读取 ChatMemory、不注入历史——追问（如"那建筑面积呢？"）
 * 不能依赖上一问上下文，会话隔离与历史记忆属 Phase 9。
 *
 * @author Tang_tzb
 */
@Data
public class AssistantChatRequest implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 会话 ID（可选；空白时服务端自动生成 UUID 并随响应返回）
     */
    private String conversationId;

    /**
     * 用户问题（禁止空白）
     */
    @NotBlank(message = "提问内容不能为空")
    private String message;
}
