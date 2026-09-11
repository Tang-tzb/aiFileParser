package com.aifp.aiagent.controller;

import com.aifp.aiagent.common.ResultCode;
import com.aifp.aiagent.dto.*;
import com.aifp.aiagent.entity.enums.FileStatus;
import com.aifp.aiagent.entity.enums.FileType;
import com.aifp.aiagent.entity.enums.ProjectStatus;
import com.aifp.aiagent.exception.BusinessException;
import com.aifp.aiagent.exception.GlobalExceptionHandler;
import com.aifp.aiagent.service.ProjectService;
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
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultHandlers.print;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@link ProjectController} 测试
 * <p>
 * 采用 standalone MockMvc：手动装配 {@link ProjectController} + {@link GlobalExceptionHandler}，
 * 不启动 Spring 上下文，可完全离线运行（无需 MySQL/DashScope Key）。
 * <p>
 * 覆盖 5 个接口各正向用例 + 关键校验失败与 BusinessException 转 Result 异常链路。
 *
 * @author Tang_tzb
 */
@ExtendWith(MockitoExtension.class)
class ProjectControllerTest {

    private static final Long PROJECT_ID = 1785900001L;
    private static final Long FILE_ID = 1785800001L;
    private final ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
    private MockMvc mockMvc;
    @Mock
    private ProjectService projectService;

    @InjectMocks
    private ProjectController projectController;

    @BeforeEach
    void setUp() {
        // standalone 装配：控制器 + 全局异常处理器，并显式提供 Jackson 转换器
        this.mockMvc = MockMvcBuilders.standaloneSetup(projectController)
                .setControllerAdvice(new GlobalExceptionHandler())
                .setMessageConverters(new MappingJackson2HttpMessageConverter(objectMapper))
                .build();
    }

    // ==================== POST /project ====================

    /**
     * 合法请求 → 200, data=projectId, service 被调用
     */
    @Test
    void create_shouldReturnProjectId() throws Exception {
        ProjectCreateDTO dto = validCreateDTO();
        when(projectService.createProject(any(ProjectCreateDTO.class))).thenReturn(PROJECT_ID);

        mockMvc.perform(post("/project")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(dto)))
                .andDo(print())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.message").value("操作成功"));

        verify(projectService).createProject(any(ProjectCreateDTO.class));
    }

    /**
     * projectName 空 → 40001, service 未被调用
     */
    @Test
    void create_shouldFailWhenProjectNameBlank() throws Exception {
        ProjectCreateDTO dto = validCreateDTO();
        dto.setProjectName("");

        mockMvc.perform(post("/project")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(dto)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(40001))
                .andExpect(jsonPath("$.message").exists());

        verifyNoInteractions(projectService);
    }

    /**
     * projectNo 空 → 40001, service 未被调用
     */
    @Test
    void create_shouldFailWhenProjectNoBlank() throws Exception {
        ProjectCreateDTO dto = validCreateDTO();
        dto.setProjectNo(" ");

        mockMvc.perform(post("/project")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(dto)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(40001));

        verifyNoInteractions(projectService);
    }

    /**
     * service 抛 PROJECT_NO_DUPLICATE → 6002
     */
    @Test
    void create_shouldReturn6002WhenProjectNoDuplicate() throws Exception {
        when(projectService.createProject(any(ProjectCreateDTO.class)))
                .thenThrow(new BusinessException(ResultCode.PROJECT_NO_DUPLICATE,
                        "项目编号已存在: PRJ-001"));

        mockMvc.perform(post("/project")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(validCreateDTO())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(6002))
                .andExpect(jsonPath("$.message").exists());
    }

    // ==================== GET /project/{id} ====================

    /**
     * 合法 id → 200, data.projectNo 校验；projectId 序列化为字符串防精度丢失
     */
    @Test
    void get_shouldReturnProjectDetail() throws Exception {
        when(projectService.getProjectById(PROJECT_ID)).thenReturn(sampleProjectVO(PROJECT_ID));

        mockMvc.perform(get("/project/{id}", PROJECT_ID))
                .andDo(print())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.projectId").value(String.valueOf(PROJECT_ID)))
                .andExpect(jsonPath("$.data.projectNo").value("PRJ-001"))
                .andExpect(jsonPath("$.data.projectName").value("职业教育园一期"))
                .andExpect(jsonPath("$.data.status").value("ACTIVE"));

        verify(projectService).getProjectById(PROJECT_ID);
    }

    /**
     * service 抛 PROJECT_NOT_FOUND → 6001
     */
    @Test
    void get_shouldReturn6001WhenNotFound() throws Exception {
        when(projectService.getProjectById(eq(9999L)))
                .thenThrow(new BusinessException(ResultCode.PROJECT_NOT_FOUND));

        mockMvc.perform(get("/project/{id}", 9999L))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(6001))
                .andExpect(jsonPath("$.message").value("项目不存在"));
    }

    // ==================== GET /project/page ====================

    /**
     * 分页查询正常 → 200，返回 records 含 2 条
     */
    @Test
    void page_normal_shouldReturnRecords() throws Exception {
        PageResult<ProjectVO> pr = PageResult.of(
                2L, 1L, 1L, 10L,
                List.of(listProjectVO(1L, "项目A"), listProjectVO(2L, "项目B")));

        when(projectService.page(any(PageQuery.class))).thenReturn(pr);

        mockMvc.perform(get("/project/page")
                        .param("pageNum", "1")
                        .param("pageSize", "10"))
                .andDo(print())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.total").value(2))
                .andExpect(jsonPath("$.data.records[0].projectName").value("项目A"))
                .andExpect(jsonPath("$.data.records[1].projectName").value("项目B"));

        verify(projectService).page(any(PageQuery.class));
    }

    /**
     * pageSize 超过 100 → 参数校验失败 → 40001
     */
    @Test
    void page_invalidPageSize_shouldReturn40001() throws Exception {
        mockMvc.perform(get("/project/page")
                        .param("pageSize", "200"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(40001));

        verifyNoInteractions(projectService);
    }

    /**
     * 空结果 → total=0, records=[]
     */
    @Test
    void page_empty_shouldReturnEmptyList() throws Exception {
        when(projectService.page(any(PageQuery.class))).thenReturn(PageResult.empty());

        mockMvc.perform(get("/project/page"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.total").value(0))
                .andExpect(jsonPath("$.data.records").isEmpty());

        verify(projectService).page(any(PageQuery.class));
    }

    // ==================== PUT /project/{id} ====================

    /**
     * 合法编辑 → 200, service 被调用
     */
    @Test
    void update_shouldReturn200() throws Exception {
        ProjectUpdateDTO dto = new ProjectUpdateDTO();
        dto.setProjectName("新名称");
        dto.setStatus(ProjectStatus.ARCHIVED);

        mockMvc.perform(put("/project/{id}", PROJECT_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(dto)))
                .andDo(print())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data").doesNotExist());

        verify(projectService).updateProject(eq(PROJECT_ID), any(ProjectUpdateDTO.class));
    }

    /**
     * service 抛 PROJECT_NOT_FOUND → 6001
     */
    @Test
    void update_shouldReturn6001WhenNotFound() throws Exception {
        doThrow(new BusinessException(ResultCode.PROJECT_NOT_FOUND))
                .when(projectService).updateProject(eq(9999L), any(ProjectUpdateDTO.class));

        mockMvc.perform(put("/project/{id}", 9999L)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new ProjectUpdateDTO())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(6001))
                .andExpect(jsonPath("$.message").value("项目不存在"));
    }

    // ==================== DELETE /project/{id} ====================

    /**
     * 正常逻辑删除 → 200, service 被调用
     */
    @Test
    void delete_shouldReturn200() throws Exception {
        mockMvc.perform(delete("/project/{id}", PROJECT_ID))
                .andDo(print())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data").doesNotExist());

        verify(projectService).deleteProject(PROJECT_ID);
    }

    /**
     * service 抛 PROJECT_NOT_FOUND → 6001
     */
    @Test
    void delete_shouldReturn6001WhenNotFound() throws Exception {
        doThrow(new BusinessException(ResultCode.PROJECT_NOT_FOUND))
                .when(projectService).deleteProject(9999L);

        mockMvc.perform(delete("/project/{id}", 9999L))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(6001))
                .andExpect(jsonPath("$.message").value("项目不存在"));
    }

    // ==================== 项目文件关联（Phase 2） ====================

    /**
     * 项目文件分页 → 200，records 含项目归属文件
     */
    @Test
    void files_success_returnsPageRecords() throws Exception {
        PageResult<FileRecordVO> pr = PageResult.of(
                1L, 1L, 1L, 10L, List.of(fileVO()));

        when(projectService.listProjectFiles(eq(PROJECT_ID), any(PageQuery.class))).thenReturn(pr);

        mockMvc.perform(get("/project/{id}/files", PROJECT_ID)
                        .param("pageNum", "1")
                        .param("pageSize", "10"))
                .andDo(print())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.records[0].fileName").value("项目申报书.pdf"))
                .andExpect(jsonPath("$.data.records[0].projectId").value(String.valueOf(PROJECT_ID)));

        verify(projectService).listProjectFiles(eq(PROJECT_ID), any(PageQuery.class));
    }

    /**
     * 项目文件分页：项目不存在 → 6001
     */
    @Test
    void files_projectNotFound_returns6001() throws Exception {
        when(projectService.listProjectFiles(eq(9999L), any(PageQuery.class)))
                .thenThrow(new BusinessException(ResultCode.PROJECT_NOT_FOUND));

        mockMvc.perform(get("/project/{id}/files", 9999L))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(6001))
                .andExpect(jsonPath("$.message").value("项目不存在"));
    }

    /**
     * 关联文件 → 200
     */
    @Test
    void associateFile_success_returns200() throws Exception {
        mockMvc.perform(post("/project/{id}/file/{fileId}", PROJECT_ID, FILE_ID))
                .andDo(print())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data").doesNotExist());

        verify(projectService).associateFile(PROJECT_ID, FILE_ID);
    }

    /**
     * 关联文件：已归属其他项目 → 6003
     */
    @Test
    void associateFile_boundToOther_returns6003() throws Exception {
        doThrow(new BusinessException(ResultCode.PROJECT_FILE_ALREADY_BOUND))
                .when(projectService).associateFile(PROJECT_ID, FILE_ID);

        mockMvc.perform(post("/project/{id}/file/{fileId}", PROJECT_ID, FILE_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(6003))
                .andExpect(jsonPath("$.message").value("文件已关联其他项目"));
    }

    /**
     * 解除关联 → 200
     */
    @Test
    void dissociateFile_success_returns200() throws Exception {
        mockMvc.perform(delete("/project/{id}/file/{fileId}", PROJECT_ID, FILE_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        verify(projectService).dissociateFile(PROJECT_ID, FILE_ID);
    }

    /**
     * 解除关联：文件未归属该项目 → 6004
     */
    @Test
    void dissociateFile_notBound_returns6004() throws Exception {
        doThrow(new BusinessException(ResultCode.PROJECT_FILE_NOT_IN_PROJECT))
                .when(projectService).dissociateFile(PROJECT_ID, FILE_ID);

        mockMvc.perform(delete("/project/{id}/file/{fileId}", PROJECT_ID, FILE_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(6004))
                .andExpect(jsonPath("$.message").value("文件不属于该项目"));
    }

    // ==================== 测试数据构造 ====================

    /**
     * 项目归属文件 VO：仅文件元数据 + projectId
     */
    private FileRecordVO fileVO() {
        FileRecordVO vo = new FileRecordVO();
        vo.setFileId(FILE_ID);
        vo.setFileName("项目申报书.pdf");
        vo.setFileType(FileType.PDF);
        vo.setProjectId(PROJECT_ID);
        vo.setStatus(FileStatus.UPLOADED);
        vo.setCreateTime(LocalDateTime.now());
        vo.setUpdateTime(LocalDateTime.now());
        return vo;
    }

    private ProjectCreateDTO validCreateDTO() {
        ProjectCreateDTO dto = new ProjectCreateDTO();
        dto.setProjectNo("PRJ-001");
        dto.setProjectName("职业教育园一期");
        dto.setDescription("职业教育园一期工程项目");
        return dto;
    }

    private ProjectVO sampleProjectVO(Long id) {
        ProjectVO vo = new ProjectVO();
        vo.setProjectId(id);
        vo.setProjectNo("PRJ-001");
        vo.setProjectName("职业教育园一期");
        vo.setDescription("职业教育园一期工程项目");
        vo.setStatus(ProjectStatus.ACTIVE);
        vo.setCreateTime(LocalDateTime.now());
        vo.setUpdateTime(LocalDateTime.now());
        return vo;
    }

    /**
     * 列表场景 ProjectVO：仅项目元数据
     */
    private ProjectVO listProjectVO(Long id, String projectName) {
        ProjectVO vo = new ProjectVO();
        vo.setProjectId(id);
        vo.setProjectNo("PRJ-" + id);
        vo.setProjectName(projectName);
        vo.setStatus(ProjectStatus.ACTIVE);
        vo.setCreateTime(LocalDateTime.now());
        vo.setUpdateTime(LocalDateTime.now());
        return vo;
    }
}
