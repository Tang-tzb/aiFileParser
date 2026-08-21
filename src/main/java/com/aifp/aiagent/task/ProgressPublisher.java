package com.aifp.aiagent.task;

import com.aifp.aiagent.dto.TaskProgress;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * 任务进度发布器
 * <p>
 * 双写 Redis：① 快照键 {@code task:progress:{taskId}}（带 TTL，供 SSE 重连首帧与查询），
 * ② Pub/Sub channel {@link #CHANNEL}（供 {@link ProgressMessageListener} 实时分发到 SSE）。
 * 一次发布即同时满足「最新状态查询」与「实时推送」两类需求。
 *
 * @author Tang_tzb
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ProgressPublisher {

    /**
     * Pub/Sub 频道：所有任务进度统一发往此频道，由监听器按 taskId 路由
     */
    public static final String CHANNEL = "task:progress";

    /**
     * 快照键前缀
     */
    private static final String SNAPSHOT_KEY_PREFIX = "task:progress:";

    private final RedisTemplate<String, Object> redisTemplate;

    @Value("${task.progress.snapshot-ttl-hours:3}")
    private int snapshotTtlHours;

    /**
     * 发布进度：写快照 + 发 Pub/Sub。
     * 写失败仅记录日志不抛异常，避免进度上报影响主流程。
     */
    public void publish(TaskProgress progress) {
        try {
            String key = snapshotKey(progress.getTaskId());
            Duration ttl = Duration.ofHours(snapshotTtlHours);
            redisTemplate.opsForValue().set(key, progress, ttl);
            redisTemplate.convertAndSend(CHANNEL, progress);
        } catch (Exception e) {
            log.warn("进度发布失败 taskId={}, percent={}：{}",
                    progress.getTaskId(), progress.getPercent(), e.getMessage());
        }
    }

    /**
     * 读取最新快照（供 SSE 重连首帧）。
     *
     * @return 快照；不存在或读取异常返回 null
     */
    public TaskProgress getSnapshot(String taskId) {
        try {
            Object value = redisTemplate.opsForValue().get(snapshotKey(taskId));
            return value instanceof TaskProgress tp ? tp : null;
        } catch (Exception e) {
            log.warn("进度快照读取失败 taskId={}：{}", taskId, e.getMessage());
            return null;
        }
    }

    private String snapshotKey(String taskId) {
        return SNAPSHOT_KEY_PREFIX + taskId;
    }
}
