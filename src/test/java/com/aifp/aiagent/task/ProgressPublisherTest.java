package com.aifp.aiagent.task;

import com.aifp.aiagent.dto.TaskProgress;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * {@link ProgressPublisher} 测试（离线，RedisTemplate mock）。
 * <p>
 * 覆盖：publish 双写快照+Pub/Sub；getSnapshot 命中/未命中/异常。
 *
 * @author Tang_tzb
 */
@ExtendWith(MockitoExtension.class)
class ProgressPublisherTest {

    @Mock
    private RedisTemplate<String, Object> redisTemplate;
    @Mock
    private ValueOperations<String, Object> valueOps;

    private ProgressPublisher publisher;

    @BeforeEach
    void setUp() {
        publisher = new ProgressPublisher(redisTemplate);
        ReflectionTestUtils.setField(publisher, "snapshotTtlHours", 3);
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
    }

    @Test
    void publish_writesSnapshotAndSendsPubSub() {
        TaskProgress progress = new TaskProgress("t1", 1L, 2L, "PARSING", 0, "解析中", null);

        publisher.publish(progress);

        verify(valueOps).set(eq("task:progress:t1"), eq(progress), any(Duration.class));
        verify(redisTemplate).convertAndSend(ProgressPublisher.CHANNEL, progress);
    }

    @Test
    void publish_redisFailure_doesNotThrow() {
        TaskProgress progress = new TaskProgress("t2", 1L, 2L, "PARSING", 0, "解析中", null);
        doThrow(new RuntimeException("Redis down"))
                .when(valueOps).set(anyString(), any(), any(Duration.class));

        publisher.publish(progress); // 不抛异常，避免影响主流程

        verify(redisTemplate, never()).convertAndSend(anyString(), any());
    }

    @Test
    void getSnapshot_hit_returnsProgress() {
        TaskProgress stored = new TaskProgress("t3", 1L, 2L, "SUCCESS", 100, "完成", null);
        when(valueOps.get("task:progress:t3")).thenReturn(stored);

        TaskProgress result = publisher.getSnapshot("t3");

        assertThat(result).isSameAs(stored);
    }

    @Test
    void getSnapshot_miss_returnsNull() {
        when(valueOps.get("task:progress:t4")).thenReturn(null);

        assertThat(publisher.getSnapshot("t4")).isNull();
    }

    @Test
    void getSnapshot_wrongType_returnsNull() {
        when(valueOps.get("task:progress:t5")).thenReturn("not a progress");

        assertThat(publisher.getSnapshot("t5")).isNull();
    }
}
