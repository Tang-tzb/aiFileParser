package com.aifp.aiagent.controller;

import com.aifp.aiagent.common.ResultCode;
import com.aifp.aiagent.exception.BusinessException;
import com.aifp.aiagent.exception.GlobalExceptionHandler;
import com.aifp.aiagent.service.FieldExtractorService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultHandlers.print;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@link FillController} 测试
 * <p>
 * 采用 standalone MockMvc：手动装配 Controller + GlobalExceptionHandler，不启动 Spring 上下文，可离线运行。
 * 覆盖 {@code POST /fill/{formId}?fileId=xxx}：正向抽取 + 表单不存在(5001) 异常路径。
 *
 * @author Tang_tzb
 */
@ExtendWith(MockitoExtension.class)
class FillControllerTest {

    private static final Long FORM_ID = 1785508135L;
    private static final Long FILE_ID = 1785800001L;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private MockMvc mockMvc;

    @Mock
    private FieldExtractorService fieldExtractorService;

    @InjectMocks
    private FillController fillController;

    @BeforeEach
    void setUp() {
        this.mockMvc = MockMvcBuilders.standaloneSetup(fillController)
                .setControllerAdvice(new GlobalExceptionHandler())
                .setMessageConverters(new MappingJackson2HttpMessageConverter(objectMapper))
                .build();
    }

    @Test
    void fill_normal_returnsExtractedMap() throws Exception {
        when(fieldExtractorService.extract(eq(FORM_ID), eq(FILE_ID)))
                .thenReturn(buildResult());

        mockMvc.perform(post("/fill/{formId}", FORM_ID).param("fileId", String.valueOf(FILE_ID)))
                .andDo(print())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.projectName").value("智慧校园"))
                .andExpect(jsonPath("$.data.amount").value(5000000));

        verify(fieldExtractorService).extract(FORM_ID, FILE_ID);
    }

    @Test
    void fill_formNotFound_returns5001() throws Exception {
        when(fieldExtractorService.extract(eq(FORM_ID), eq(FILE_ID)))
                .thenThrow(new BusinessException(ResultCode.FORM_NOT_FOUND));

        mockMvc.perform(post("/fill/{formId}", FORM_ID).param("fileId", String.valueOf(FILE_ID)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(5001))
                .andExpect(jsonPath("$.message").exists());

        verify(fieldExtractorService).extract(FORM_ID, FILE_ID);
    }

    private Map<String, Object> buildResult() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("projectName", "智慧校园");
        map.put("amount", 5000000);
        return map;
    }
}
