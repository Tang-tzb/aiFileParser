package com.aifp.aiagent.controller;

import com.aifp.aiagent.common.Result;
import com.aifp.aiagent.dto.AssistantChatRequest;
import com.aifp.aiagent.dto.AssistantChatResponse;
import com.aifp.aiagent.service.ProjectAssistantService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

/**
 * 项目助手接口（需求 §十三）
 * <p>
 * 完整路径前缀：/aifp（context-path）+ /project
 * <p>
 * 权限说明：本层不做权限判断，访问控制统一由 Service 层经
 * {@code ProjectService.getProjectById} 守门（403/6001）。
 * <p>
 * 会话说明：Phase 8 无状态——conversationId 仅透传/自动生成，会话隔离与
 * 历史记忆属 Phase 9。
 *
 * @author Tang_tzb
 */
@RestController
@RequestMapping("/project")
@RequiredArgsConstructor
public class ProjectAssistantController {

    private final ProjectAssistantService projectAssistantService;

    /**
     * 项目助手对话问答
     */
    @PostMapping("/{projectId}/assistant/chat")
    public Result<AssistantChatResponse> chat(@PathVariable Long projectId,
                                              @Valid @RequestBody AssistantChatRequest request) {
        return Result.success(projectAssistantService.chat(projectId, request));
    }
}
