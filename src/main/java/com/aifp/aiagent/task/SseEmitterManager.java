package com.aifp.aiagent.task;

import com.aifp.aiagent.dto.TaskProgress;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * SSE emitter 注册表
 * <p>
 * 进程内按 taskId 持有 {@link SseEmitter}，{@link ProgressMessageListener} 收到 Redis
 * Pub/Sub 消息后调用 {@link #send} 路由到对应连接。emitter 完成/超时/异常自动移除，避免内存泄漏。
 *
 * @author Tang_tzb
 */
@Slf4j
@Component
public class SseEmitterManager {

    private final ConcurrentMap<String, SseEmitter> emitters = new ConcurrentHashMap<>();

    /**
     * 注册一个 SSE emitter 并绑定生命周期回调。
     *
     * @param taskId  任务 ID
     * @param timeout 超时毫秒
     * @return 可返回给控制器的 emitter
     */
    public SseEmitter register(String taskId, long timeout) {
        SseEmitter emitter = createEmitter(timeout);
        emitters.put(taskId, emitter);
        emitter.onCompletion(() -> remove(taskId));
        emitter.onTimeout(() -> {
            remove(taskId);
            emitter.complete();
        });
        emitter.onError(throwable -> remove(taskId));
        return emitter;
    }

    /**
     * 创建 emitter（测试可覆盖以注入 mock，便于确定性验证 send 路由）。
     */
    protected SseEmitter createEmitter(long timeout) {
        return new SseEmitter(timeout);
    }

    /**
     * 向指定任务的 SSE 连接发送一帧进度。无连接或发送失败时安全忽略。
     */
    public void send(String taskId, TaskProgress progress) {
        SseEmitter emitter = emitters.get(taskId);
        if (emitter == null) {
            return;
        }
        try {
            emitter.send(SseEmitter.event().name("progress").data(progress));
        } catch (IOException | IllegalStateException e) {
            log.debug("SSE 发送失败 taskId={}：{}", taskId, e.getMessage());
            remove(taskId);
        }
    }

    /**
     * 主动完成并移除 emitter（终态时调用）。
     */
    public void complete(String taskId) {
        SseEmitter emitter = emitters.remove(taskId);
        if (emitter != null) {
            emitter.complete();
        }
    }

    /**
     * 是否存在活跃连接（便于测试与诊断）。
     */
    public boolean isActive(String taskId) {
        return emitters.containsKey(taskId);
    }

    private void remove(String taskId) {
        emitters.remove(taskId);
    }
}
