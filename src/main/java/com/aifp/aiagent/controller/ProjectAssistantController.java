package com.aifp.aiagent.controller;

import com.aifp.aiagent.dto.AssistantChatRequest;
import com.aifp.aiagent.service.ProjectAssistantService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * 项目助手接口（需求 §十三；Phase K 流式输出）
 * <p>
 * 完整路径前缀：/aifp（context-path）+ /project
 * <p>
 * 权限说明：本层不做权限判断，访问控制统一由 Service 层经
 * {@code ProjectService.getProjectById} 守门（403/6001）。守门失败发生在 SSE
 * 连接建立之前，以统一 Result JSON 错误返回（HTTP 状态 200 + 业务码）。
 * <p>
 * 流式协议（Phase K）：守门通过后以 text/event-stream 建立连接，事件
 * {@code delta}=回答文本增量（{"delta":"..."}）、{@code final}=完整响应
 * （AssistantChatResponse JSON）、{@code error}=失败消息（{"message":"..."}）。
 *
 * @author Tang_tzb
 */
@RestController
@RequestMapping("/project")
@RequiredArgsConstructor
public class ProjectAssistantController {

    private final ProjectAssistantService projectAssistantService;

    /**
     * 项目助手对话问答（SSE 流式，Phase K）
     */
    @PostMapping(value = "/{projectId}/assistant/chat",
            produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter chat(@PathVariable Long projectId,
                           @Valid @RequestBody AssistantChatRequest request) {
        return projectAssistantService.chatStream(projectId, request);
    }
}
