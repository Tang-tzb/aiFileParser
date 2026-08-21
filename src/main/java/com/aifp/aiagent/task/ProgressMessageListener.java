package com.aifp.aiagent.task;

import com.aifp.aiagent.dto.TaskProgress;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.data.redis.serializer.RedisSerializer;
import org.springframework.stereotype.Component;

/**
 * Redis Pub/Sub 进度监听器
 * <p>
 * 订阅 {@link ProgressPublisher#CHANNEL}，反序列化 {@link TaskProgress} 后
 * 经 {@link SseEmitterManager} 路由到对应 SSE 连接；终态时自动 complete emitter。
 * 由 {@link com.aifp.aiagent.config.RedisConfig} 注册到 {@code RedisMessageListenerContainer}。
 *
 * @author Tang_tzb
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ProgressMessageListener implements MessageListener {

    /**
     * 复用 RedisTemplate 的值序列化器，保证发布/订阅端对称反序列化
     */
    private final RedisSerializer<Object> valueSerializer;
    private final SseEmitterManager sseEmitterManager;

    @Override
    public void onMessage(Message message, byte[] pattern) {
        try {
            Object payload = valueSerializer.deserialize(message.getBody());
            if (!(payload instanceof TaskProgress progress)) {
                return;
            }
            sseEmitterManager.send(progress.getTaskId(), progress);
            if (progress.isTerminal()) {
                sseEmitterManager.complete(progress.getTaskId());
            }
        } catch (Exception e) {
            log.warn("进度消息处理失败：{}", e.getMessage());
        }
    }
}
