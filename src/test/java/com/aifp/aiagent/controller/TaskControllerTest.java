package com.aifp.aiagent.controller;

import com.aifp.aiagent.dto.TaskStartVO;
import com.aifp.aiagent.exception.GlobalExceptionHandler;
import com.aifp.aiagent.service.ParseTaskService;
import com.aifp.aiagent.task.ProgressPublisher;
import com.aifp.aiagent.task.SseEmitterManager;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultHandlers.print;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@link TaskController} 测试（离线 standalone MockMvc）。
 * <p>
 * POST /task 走 MockMvc 验证正向 + 参数校验(40001)；
 * GET /task/progress 直调方法验证返回 emitter + register/getSnapshot 调用（避免 SSE 异步调度）。
 *
 * @author Tang_tzb
 */
@ExtendWith(MockitoExtension.class)
class TaskControllerTest {

    private static final Long FORM_ID = 1785508135L;
    private static final Long FILE_ID = 1785800001L;

    private final ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
    private MockMvc mockMvc;

    @Mock
    private ParseTaskService parseTaskService;
    @Mock
    private SseEmitterManager sseEmitterManager;
    @Mock
    private ProgressPublisher progressPublisher;

    @InjectMocks
    private TaskController taskController;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(taskController, "sseTimeoutMs", 1800000L);
        this.mockMvc = MockMvcBuilders.standaloneSetup(taskController)
                .setControllerAdvice(new GlobalExceptionHandler())
                .setMessageConverters(new MappingJackson2HttpMessageConverter(objectMapper))
                .build();
    }

    @Test
    void start_valid_returnsTaskId() throws Exception {
        when(parseTaskService.start(FORM_ID, FILE_ID))
                .thenReturn(new TaskStartVO("task-1", FILE_ID, FORM_ID, LocalDateTime.now()));

        mockMvc.perform(post("/task")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"formId\":" + FORM_ID + ",\"fileId\":" + FILE_ID + "}"))
                .andDo(print())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.taskId").value("task-1"))
                .andExpect(jsonPath("$.data.formId").value(String.valueOf(FORM_ID)))
                .andExpect(jsonPath("$.data.fileId").value(String.valueOf(FILE_ID)));

        verify(parseTaskService).start(FORM_ID, FILE_ID);
    }

    @Test
    void start_missingFormId_returns40001() throws Exception {
        mockMvc.perform(post("/task")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"fileId\":" + FILE_ID + "}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(40001));

        verify(parseTaskService, never()).start(anyLong(), anyLong());
    }

    /**
     * 直调 progress()：register 返回 mock emitter、无快照 → 返回 emitter，不发送首帧。
     */
    @Test
    void progress_noSnapshot_returnsEmitterAndRegisterCalled() {
        SseEmitter mockEmitter = mock(SseEmitter.class);
        when(sseEmitterManager.register(eq("task-1"), anyLong())).thenReturn(mockEmitter);
        when(progressPublisher.getSnapshot("task-1")).thenReturn(null);

        SseEmitter emitter = taskController.progress("task-1");

        assertThat(emitter).isSameAs(mockEmitter);
        verify(sseEmitterManager).register(eq("task-1"), eq(1800000L));
        verify(progressPublisher).getSnapshot("task-1");
        verify(sseEmitterManager, never()).complete(anyString());
    }
}
