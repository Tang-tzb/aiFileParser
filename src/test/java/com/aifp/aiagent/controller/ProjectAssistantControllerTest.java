package com.aifp.aiagent.controller;

import com.aifp.aiagent.common.ResultCode;
import com.aifp.aiagent.dto.AssistantChatRequest;
import com.aifp.aiagent.dto.AssistantChatResponse;
import com.aifp.aiagent.dto.AssistantReferenceVO;
import com.aifp.aiagent.dto.ProjectStructuredFactsVO;
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
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@link ProjectAssistantController} 测试（standalone MockMvc，Phase 8 T5）
 * <p>
 * 覆盖：参数校验 400（message 缺失/空白）；200 透传 VO JSON 形状
 * （Long → String 序列化防前端精度丢失）；权限异常透传由 Service 层抛出。
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
     * 正常问答 → 200 透传响应；Long ID 序列化为字符串
     * （usedProjects/usedFiles/sourceFileId/projectFormId）
     */
    @Test
    void chat_success_returnsResponseJsonShape() throws Exception {
        when(projectAssistantService.chat(eq(PROJECT_ID), any(AssistantChatRequest.class)))
                .thenReturn(buildResponse());

        mockMvc.perform(post("/project/{projectId}/assistant/chat", PROJECT_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"conversationId\":\"conv-1\",\"message\":\"这个项目总投资是多少？\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.conversationId").value("conv-1"))
                .andExpect(jsonPath("$.data.answer").value("总投资约100万元。"))
                .andExpect(jsonPath("$.data.usedProjects[0]").value(String.valueOf(PROJECT_ID)))
                .andExpect(jsonPath("$.data.usedFiles[0]").value("1785800001"))
                .andExpect(jsonPath("$.data.references[0].type").value("STRUCTURED"))
                .andExpect(jsonPath("$.data.references[0].sourceFileId").value("1785800001"))
                .andExpect(jsonPath("$.data.references[0].sourceFileName").value("预算说明书.pdf"))
                .andExpect(jsonPath("$.data.structuredData[0].projectFormId").value("1785600001"));
    }

    /**
     * Service 层权限异常（403）透传为统一 Result 结构
     */
    @Test
    void chat_accessDenied_propagatesForbidden() throws Exception {
        when(projectAssistantService.chat(eq(PROJECT_ID), any(AssistantChatRequest.class)))
                .thenThrow(new BusinessException(ResultCode.FORBIDDEN));

        mockMvc.perform(post("/project/{projectId}/assistant/chat", PROJECT_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"问题\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(ResultCode.FORBIDDEN.getCode()));
    }

    // ==================== 测试辅助 ====================

    private AssistantChatResponse buildResponse() {
        AssistantReferenceVO ref = new AssistantReferenceVO();
        ref.setType(AssistantReferenceVO.TYPE_STRUCTURED);
        ref.setFieldCode("total_investment");
        ref.setRawValue("100万");
        ref.setNormalizedValue("1000000");
        ref.setSourceFileId(1785800001L);
        ref.setSourceFileName("预算说明书.pdf");
        ProjectStructuredFactsVO.Field field = new ProjectStructuredFactsVO.Field();
        field.setProjectFormId(1785600001L);
        field.setFieldCode("total_investment");
        AssistantChatResponse resp = new AssistantChatResponse();
        resp.setConversationId("conv-1");
        resp.setAnswer("总投资约100万元。");
        resp.setReferences(List.of(ref));
        resp.setStructuredData(List.of(field));
        resp.setUsedProjects(List.of(PROJECT_ID));
        resp.setUsedFiles(List.of(1785800001L));
        return resp;
    }
}
