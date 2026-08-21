package com.aifp.aiagent.task;

import com.aifp.aiagent.dto.TaskProgress;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter.SseEventBuilder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * {@link SseEmitterManager} 测试（离线）。
 * <p>
 * 通过覆盖 {@link SseEmitterManager#createEmitter} 注入 mock emitter，
 * 确定性验证 register/send/complete 路由与移除。
 *
 * @author Tang_tzb
 */
class SseEmitterManagerTest {

    private SseEmitter mockEmitter;
    private SseEmitterManager manager;

    @BeforeEach
    void setUp() {
        mockEmitter = mock(SseEmitter.class);
        // 子类覆盖工厂，注入 mock emitter
        manager = new SseEmitterManager() {
            @Override
            protected SseEmitter createEmitter(long timeout) {
                return mockEmitter;
            }
        };
    }

    @Test
    void register_addsToActive() {
        SseEmitter emitter = manager.register("t1", 10_000);

        assertThat(emitter).isSameAs(mockEmitter);
        assertThat(manager.isActive("t1")).isTrue();
    }

    @Test
    void complete_removesEmitter() {
        manager.register("t1", 10_000);
        assertThat(manager.isActive("t1")).isTrue();

        manager.complete("t1");

        assertThat(manager.isActive("t1")).isFalse();
        verify(mockEmitter).complete();
    }

    @Test
    void complete_unknownTask_noOp() {
        manager.complete("nope");
        assertThat(manager.isActive("nope")).isFalse();
    }

    @Test
    void send_knownTask_routesToEmitter() throws Exception {
        manager.register("t1", 10_000);
        TaskProgress progress = new TaskProgress("t1", 1L, 2L, "PARSING", 0, "解析中", null);

        manager.send("t1", progress);

        verify(mockEmitter).send(any(SseEventBuilder.class));
        assertThat(manager.isActive("t1")).isTrue();
    }

    @Test
    void send_unknownTask_noOp() throws Exception {
        TaskProgress progress = new TaskProgress("unknown", 1L, 2L, "PARSING", 0, "解析中", null);

        manager.send("unknown", progress);

        verify(mockEmitter, never()).send(any(SseEventBuilder.class));
    }

    @Test
    void send_ioFailure_removesEmitter() throws Exception {
        manager.register("t1", 10_000);
        doThrow(new java.io.IOException("client gone"))
                .when(mockEmitter).send(any(SseEventBuilder.class));
        TaskProgress progress = new TaskProgress("t1", 1L, 2L, "PARSING", 0, "解析中", null);

        manager.send("t1", progress);

        assertThat(manager.isActive("t1")).isFalse();
    }
}
