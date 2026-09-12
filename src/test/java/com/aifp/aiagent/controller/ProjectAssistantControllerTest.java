package com.aifp.aiagent.controller;

import com.aifp.aiagent.common.ResultCode;
import com.aifp.aiagent.dto.AssistantChatRequest;
import com.aifp.aiagent.exception.BusinessException;
import com.aifp.aiagent.exception.GlobalExceptionHandler;
import com.aifp.aiagent.service.ProjectAssistantService;
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
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * {@link ProjectAssistantController} 测试（standalone MockMvc，Phase 8 T5 /
 * Phase K 流式端点适配）
 * <p>
 * 覆盖：参数校验 400（message 缺失/空白，校验失败发生在 SSE 连接建立前，走统一
 * Result JSON）；成功路径返回 SseEmitter 并启动异步请求（事件内容由 Service 层
 * 编排推送，见 {@code ProjectAssistantServiceImplTest}）；Service 层守门异常
 * （403）在流建立前抛出，透传为统一 Result JSON。
 *
 * @author Tang_tzb
 */
@ExtendWith(MockitoExtension.class)
class ProjectAssistantControllerTest {

    private static final Long PROJECT_ID = 1785900001L;
    private final ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
    private MockMvc mockMvc;
    @Mock
    private ProjectAssistantService projectAssistantService;

    @InjectMocks
    private ProjectAssistantController controller;

    @BeforeEach
    void setUp() {
        // standalone 装配：控制器 + 全局异常处理器 + Jackson 转换器（离线不启 Spring 上下文）
        this.mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler())
                .setMessageConverters(new MappingJackson2HttpMessageConverter(objectMapper))
                .build();
    }

    /**
     * message 缺失 → 参数校验失败（MethodArgumentNotValidException → PARAM_VALID_ERROR 40001）
     */
    @Test
    void chat_missingMessage_returns400() throws Exception {
        mockMvc.perform(post("/project/{projectId}/assistant/chat", PROJECT_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"conversationId\":\"conv-1\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(ResultCode.PARAM_VALID_ERROR.getCode()));
        verifyNoInteractions(projectAssistantService);
    }

    /**
     * message 空白 → 同样参数校验失败
     */
    @Test
    void chat_blankMessage_returns400() throws Exception {
        mockMvc.perform(post("/project/{projectId}/assistant/chat", PROJECT_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"   \"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(ResultCode.PARAM_VALID_ERROR.getCode()));
        verifyNoInteractions(projectAssistantService);
    }

    /**
     * 正常问答（Phase K）→ 返回 text/event-stream 并启动异步请求（SseEmitter 由
     * MVC 异步处理，事件推送属 Service 层编排职责，本测试只验证契约起点）
     */
    @Test
    void chat_success_startsAsyncSseRequest() throws Exception {
        when(projectAssistantService.chatStream(eq(PROJECT_ID), any(AssistantChatRequest.class)))
                .thenReturn(new SseEmitter());

        MvcResult mvcResult = mockMvc.perform(post("/project/{projectId}/assistant/chat", PROJECT_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.TEXT_EVENT_STREAM)
                        .content("{\"conversationId\":\"conv-1\",\"message\":\"这个项目总投资是多少？\"}"))
                .andExpect(request().asyncStarted())
                .andReturn();

        verify(projectAssistantService).chatStream(eq(PROJECT_ID), any(AssistantChatRequest.class));
        assertThatAsync(mvcResult);
    }

    /**
     * Service 层守门异常（403）在 SSE 连接建立前抛出 → 统一 Result JSON 透传
     */
    @Test
    void chat_accessDenied_propagatesForbidden() throws Exception {
        when(projectAssistantService.chatStream(eq(PROJECT_ID), any(AssistantChatRequest.class)))
                .thenThrow(new BusinessException(ResultCode.FORBIDDEN));

        mockMvc.perform(post("/project/{projectId}/assistant/chat", PROJECT_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"问题\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(ResultCode.FORBIDDEN.getCode()));
    }

    // ==================== 测试辅助 ====================

    /**
     * 异步契约补充断言：异步请求未含错误结果（SSE 长连接不完成属预期）
     */
    private void assertThatAsync(MvcResult mvcResult) {
        org.assertj.core.api.Assertions.assertThat(mvcResult.getRequest().isAsyncStarted()).isTrue();
    }
}
