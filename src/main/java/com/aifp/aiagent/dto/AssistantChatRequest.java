package com.aifp.aiagent.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * 项目助手对话请求（需求 §十三；Phase 9 会话隔离生效）
 * <p>
 * Phase 9：{@code conversationId} 为真正会话标识——配合路径 projectId 构成隔离键
 * {@code assistant:{projectId}:{conversationId}}（§三十三），同一 conversationId
 * 跨 projectId 必然是不同会话；服务端注入最近 N 轮历史用于指代消解，
 * 历史不是项目事实来源（事实每轮重新拉取）。
 *
 * @author Tang_tzb
 */
@Data
public class AssistantChatRequest implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 会话 ID（可选；空白时服务端自动生成 UUID 并随响应返回）。
     * 仅在相同 projectId 下代表同一会话；携带其他项目的 conversationId 不会
     * 读到该项目的历史（隔离键含 projectId）
     */
    private String conversationId;

    /**
     * 用户问题（禁止空白）
     */
    @NotBlank(message = "提问内容不能为空")
    private String message;
}
