package com.aifp.aiagent.controller;

import com.aifp.aiagent.common.ResultCode;
import com.aifp.aiagent.dto.*;
import com.aifp.aiagent.entity.enums.FieldType;
import com.aifp.aiagent.exception.BusinessException;
import com.aifp.aiagent.exception.GlobalExceptionHandler;
import com.aifp.aiagent.service.FormService;
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

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultHandlers.print;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@link FormController} 测试
 * <p>
 * 采用 standalone MockMvc：手动装配 {@link FormController} + {@link GlobalExceptionHandler}，
 * 不启动 Spring 上下文，因此无需排除 DashScope 自动配置 / DataSource / {@code @MapperScan}，
 * 可完全离线运行（无需 MySQL/DashScope Key）。
 * <p>
 * 覆盖 4 个接口各 1 个正向用例 + 关键校验失败与 BusinessException 转 Result 异常链路。
 *
 * @author Tang_tzb
 */
@ExtendWith(MockitoExtension.class)
class FormControllerTest {

    private static final Long FORM_ID = 1785508135L;
    private static final Long FIELD_ID = 1785508200L;
    private static final Long NEW_FIELD_ID = 1785508300L;
    private final ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
    private MockMvc mockMvc;
    @Mock
    private FormService formService;

    @InjectMocks
    private FormController formController;

    @BeforeEach
    void setUp() {
        // standalone 装配：控制器 + 全局异常处理器，并显式提供 Jackson 转换器
        this.mockMvc = MockMvcBuilders.standaloneSetup(formController)
                .setControllerAdvice(new GlobalExceptionHandler())
                .setMessageConverters(new MappingJackson2HttpMessageConverter(objectMapper))
                .build();
    }

    // ==================== POST /form/create ====================

    /**
     * 合法请求(含字段列表) → 200, data=formId, service 被调用
     */
    @Test
    void createForm_shouldReturnFormId() throws Exception {
        FormCreateDTO dto = validCreateDTO();
        when(formService.createForm(any(FormCreateDTO.class))).thenReturn(FORM_ID);

        mockMvc.perform(post("/form/create")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(dto)))
                .andDo(print())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.message").value("操作成功"))
                .andExpect(jsonPath("$.data").value(FORM_ID.intValue()));

        verify(formService).createForm(any(FormCreateDTO.class));
    }

    /**
     * formName 空 → 40001, service 未被调用
     */
    @Test
    void createForm_shouldFailWhenFormNameBlank() throws Exception {
        FormCreateDTO dto = validCreateDTO();
        dto.setFormName("");

        mockMvc.perform(post("/form/create")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(dto)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(40001))
                .andExpect(jsonPath("$.message").exists());

        verifyNoInteractions(formService);
    }

    /**
     * fieldCode 含非法字符 → 40001
     */
    @Test
    void createForm_shouldFailWhenFieldCodeInvalid() throws Exception {
        FormCreateDTO dto = validCreateDTO();
        dto.getFields().get(0).setFieldCode("1_invalid");

        mockMvc.perform(post("/form/create")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(dto)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(40001));

        verifyNoInteractions(formService);
    }

    // ==================== GET /form/{id} ====================

    /**
     * 合法 id → 200, data.formName 校验
     */
    @Test
    void getForm_shouldReturnFormDetail() throws Exception {
        when(formService.getFormById(FORM_ID)).thenReturn(sampleFormVO(FORM_ID));

        mockMvc.perform(get("/form/{id}", FORM_ID))
                .andDo(print())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.formId").value(String.valueOf(FORM_ID)))
                .andExpect(jsonPath("$.data.formName").value("项目申报表"))
                .andExpect(jsonPath("$.data.fields[0].fieldCode").value("projectName"));

        verify(formService).getFormById(FORM_ID);
    }

    /**
     * service 抛 FORM_NOT_FOUND → 5001
     */
    @Test
    void getForm_shouldReturn5001WhenNotFound() throws Exception {
        when(formService.getFormById(eq(9999L)))
                .thenThrow(new BusinessException(ResultCode.FORM_NOT_FOUND));

        mockMvc.perform(get("/form/{id}", 9999L))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(5001))
                .andExpect(jsonPath("$.message").value("表单不存在"));
    }

    // ==================== GET /form/page ====================

    /**
     * 分页查询正常 → 200，返回 records 含 2 条（列表场景 fields 为空）
     */
    @Test
    void page_normal_shouldReturnRecords() throws Exception {
        PageResult<FormVO> pr = PageResult.of(
                2L, 1L, 1L, 10L,
                List.of(listFormVO(1L, "申报表A"), listFormVO(2L, "申报表B")));

        when(formService.page(any(PageQuery.class))).thenReturn(pr);

        mockMvc.perform(get("/form/page")
                        .param("pageNum", "1")
                        .param("pageSize", "10"))
                .andDo(print())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.total").value(2))
                .andExpect(jsonPath("$.data.records[0].formName").value("申报表A"))
                .andExpect(jsonPath("$.data.records[1].formName").value("申报表B"))
                // 列表场景 fields 为空，前端走详情接口获取字段
                .andExpect(jsonPath("$.data.records[0].fields").isEmpty());

        verify(formService).page(any(PageQuery.class));
    }

    /**
     * pageSize 超过 100 → 参数校验失败 → 40001
     */
    @Test
    void page_invalidPageSize_shouldReturn40001() throws Exception {
        mockMvc.perform(get("/form/page")
                        .param("pageSize", "200"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(40001));

        verifyNoInteractions(formService);
    }

    /**
     * 空结果 → total=0, records=[]
     */
    @Test
    void page_empty_shouldReturnEmptyList() throws Exception {
        when(formService.page(any(PageQuery.class))).thenReturn(PageResult.empty());

        mockMvc.perform(get("/form/page"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.total").value(0))
                .andExpect(jsonPath("$.data.records").isEmpty());

        verify(formService).page(any(PageQuery.class));
    }

    // ==================== POST /form/{id}/field ====================

    /**
     * 合法字段 → 200, data=fieldId
     */
    @Test
    void addField_shouldReturnFieldId() throws Exception {
        FormFieldCreateDTO dto = singleFieldDTO("remark");
        when(formService.addField(eq(FORM_ID), any(FormFieldCreateDTO.class))).thenReturn(NEW_FIELD_ID);

        mockMvc.perform(post("/form/{id}/field", FORM_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(dto)))
                .andDo(print())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data").value(NEW_FIELD_ID.intValue()));

        verify(formService).addField(eq(FORM_ID), any(FormFieldCreateDTO.class));
    }

    /**
     * fieldType 空 → 40001
     */
    @Test
    void addField_shouldFailWhenFieldTypeNull() throws Exception {
        FormFieldCreateDTO dto = singleFieldDTO("remark");
        dto.setFieldType(null);

        mockMvc.perform(post("/form/{id}/field", FORM_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(dto)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(40001));

        verifyNoInteractions(formService);
    }

    /**
     * service 抛 FIELD_CODE_DUPLICATE → 5002
     */
    @Test
    void addField_shouldReturn5002WhenCodeDuplicate() throws Exception {
        FormFieldCreateDTO dto = singleFieldDTO("projectName");
        when(formService.addField(eq(FORM_ID), any(FormFieldCreateDTO.class)))
                .thenThrow(new BusinessException(ResultCode.FIELD_CODE_DUPLICATE,
                        "字段编码已存在: projectName"));

        mockMvc.perform(post("/form/{id}/field", FORM_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(dto)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(5002))
                .andExpect(jsonPath("$.message").exists());
    }

    // ==================== DELETE /form/{id}/field/{fieldId} ====================

    /**
     * 正常软删 → 200, service 被调用
     */
    @Test
    void deleteField_shouldReturn200() throws Exception {
        mockMvc.perform(delete("/form/{id}/field/{fieldId}", FORM_ID, FIELD_ID))
                .andDo(print())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data").doesNotExist());

        verify(formService).deleteField(FORM_ID, FIELD_ID);
    }

    /**
     * service 抛 FIELD_NOT_FOUND → 5003
     */
    @Test
    void deleteField_shouldReturn5003WhenFieldNotFound() throws Exception {
        doThrow(new BusinessException(ResultCode.FIELD_NOT_FOUND))
                .when(formService).deleteField(FORM_ID, FIELD_ID);

        mockMvc.perform(delete("/form/{id}/field/{fieldId}", FORM_ID, FIELD_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(5003))
                .andExpect(jsonPath("$.message").value("字段不存在"));
    }

    // ==================== DELETE /form/{id} ====================

    /**
     * 正常软删表单（级联软删字段）→ 200，service 被调用
     */
    @Test
    void deleteForm_shouldReturn200() throws Exception {
        mockMvc.perform(delete("/form/{id}", FORM_ID))
                .andDo(print())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data").doesNotExist());

        verify(formService).deleteForm(FORM_ID);
    }

    /**
     * service 抛 FORM_NOT_FOUND → 5001
     */
    @Test
    void deleteForm_shouldReturn5001WhenNotFound() throws Exception {
        doThrow(new BusinessException(ResultCode.FORM_NOT_FOUND))
                .when(formService).deleteForm(9999L);

        mockMvc.perform(delete("/form/{id}", 9999L))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(5001))
                .andExpect(jsonPath("$.message").value("表单不存在"));
    }

    // ==================== 测试数据构造 ====================

    private FormCreateDTO validCreateDTO() {
        FormCreateDTO dto = new FormCreateDTO();
        dto.setFormName("项目申报表");
        dto.setDescription("投资申报表单");

        FormFieldCreateDTO name = new FormFieldCreateDTO();
        name.setFieldName("项目名称");
        name.setFieldCode("projectName");
        name.setFieldType(FieldType.STRING);
        name.setRequired(true);
        name.setSort(1);

        FormFieldCreateDTO amount = new FormFieldCreateDTO();
        amount.setFieldName("投资金额");
        amount.setFieldCode("investAmount");
        amount.setFieldType(FieldType.DECIMAL);
        amount.setRequired(true);
        amount.setSort(2);

        dto.setFields(List.of(name, amount));
        return dto;
    }

    private FormVO sampleFormVO(Long id) {
        FormVO vo = new FormVO();
        vo.setFormId(id);
        vo.setFormName("项目申报表");
        vo.setDescription("投资申报表单");
        vo.setCreateTime(LocalDateTime.now());
        vo.setUpdateTime(LocalDateTime.now());

        FormFieldVO name = new FormFieldVO();
        name.setFieldId(FIELD_ID);
        name.setFieldName("项目名称");
        name.setFieldCode("projectName");
        name.setFieldType(FieldType.STRING);
        name.setRequired(true);
        name.setSort(1);

        FormFieldVO amount = new FormFieldVO();
        amount.setFieldId(FIELD_ID + 1);
        amount.setFieldName("投资金额");
        amount.setFieldCode("investAmount");
        amount.setFieldType(FieldType.DECIMAL);
        amount.setRequired(true);
        amount.setSort(2);

        vo.setFields(List.of(name, amount));
        return vo;
    }

    private FormFieldCreateDTO singleFieldDTO(String code) {
        FormFieldCreateDTO dto = new FormFieldCreateDTO();
        dto.setFieldName("备注");
        dto.setFieldCode(code);
        dto.setFieldType(FieldType.STRING);
        dto.setRequired(false);
        dto.setSort(5);
        return dto;
    }

    /**
     * 列表场景 FormVO：仅表单元数据，fields 为空
     */
    private FormVO listFormVO(Long id, String formName) {
        FormVO vo = new FormVO();
        vo.setFormId(id);
        vo.setFormName(formName);
        vo.setDescription("desc");
        vo.setCreateTime(LocalDateTime.now());
        vo.setUpdateTime(LocalDateTime.now());
        vo.setFields(Collections.emptyList());
        return vo;
    }
}
