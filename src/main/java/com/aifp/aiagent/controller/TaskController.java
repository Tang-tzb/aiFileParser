package com.aifp.aiagent.controller;

import com.aifp.aiagent.common.Result;
import com.aifp.aiagent.dto.TaskProgress;
import com.aifp.aiagent.dto.TaskStartRequest;
import com.aifp.aiagent.dto.TaskStartVO;
import com.aifp.aiagent.service.ParseTaskService;
import com.aifp.aiagent.task.ProgressPublisher;
import com.aifp.aiagent.task.SseEmitterManager;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;

/**
 * 异步任务接口
 * <p>
 * 完整路径前缀：/aifp（context-path）+ /task
 * <ul>
 *   <li>POST /task —— 启动异步解析任务，返回 taskId</li>
 *   <li>GET  /task/progress/{taskId} —— SSE 订阅实时进度</li>
 * </ul>
 *
 * @author Tang_tzb
 */
@Slf4j
@RestController
@RequestMapping("/task")
@RequiredArgsConstructor
public class TaskController {

    private final ParseTaskService parseTaskService;
    private final SseEmitterManager sseEmitterManager;
    private final ProgressPublisher progressPublisher;

    @Value("${task.sse.timeout-ms:1800000}")
    private long sseTimeoutMs;

    /**
     * 启动异步解析任务（绑定 formId + fileId），立即返回 taskId。
     */
    @PostMapping
    public Result<TaskStartVO> start(@Valid @RequestBody TaskStartRequest request) {
        return Result.success(parseTaskService.start(request.getFormId(), request.getFileId()));
    }

    /**
     * SSE 订阅任务进度。
     * <p>
     * 连接建立即发当前快照（断线重连可立即拿到最新状态），后续帧由 Redis Pub/Sub
     * 经 {@link SseEmitterManager} 推送；终态自动关闭连接。
     */
    @GetMapping(value = "/progress/{taskId}", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter progress(@PathVariable String taskId) {
        SseEmitter emitter = sseEmitterManager.register(taskId, sseTimeoutMs);
        sendSnapshotIfPresent(taskId, emitter);
        return emitter;
    }

    /**
     * 发送当前快照首帧；若任务已终态则立即完成连接。
     */
    private void sendSnapshotIfPresent(String taskId, SseEmitter emitter) {
        TaskProgress snapshot = progressPublisher.getSnapshot(taskId);
        if (snapshot == null) {
            return;
        }
        try {
            emitter.send(SseEmitter.event().name("progress").data(snapshot));
            if (snapshot.isTerminal()) {
                sseEmitterManager.complete(taskId);
            }
        } catch (IOException e) {
            log.debug("SSE 首帧发送失败 taskId={}：{}", taskId, e.getMessage());
            sseEmitterManager.complete(taskId);
        }
    }
}
