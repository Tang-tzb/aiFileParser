package com.aifp.aiagent.controller;

import com.aifp.aiagent.common.ResultCode;
import com.aifp.aiagent.dto.FileUploadVO;
import com.aifp.aiagent.entity.enums.FileStatus;
import com.aifp.aiagent.entity.enums.FileType;
import com.aifp.aiagent.exception.BusinessException;
import com.aifp.aiagent.exception.GlobalExceptionHandler;
import com.aifp.aiagent.service.FileService;
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
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.multipart.MultipartFile;

import java.time.LocalDateTime;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultHandlers.print;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@link FileController} 测试
 * <p>
 * 采用 standalone MockMvc：手动装配 {@link FileController} + {@link GlobalExceptionHandler}，
 * 不启动 Spring 上下文，无需 MySQL/DashScope Key，可离线运行。
 * <p>
 * 覆盖 {@code POST /file/upload}：3 种支持类型(PDF/Excel/Word)正向 + 2 条异常路径
 * (不支持类型→2002、空文件/上传失败→2003)，验证 multipart 绑定、枚举序列化与
 * BusinessException 转 Result 链路。
 *
 * @author aiFileParser
 */
@ExtendWith(MockitoExtension.class)
class FileControllerTest {

    private static final Long FILE_ID = 1785800001L;
    private final ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
    private MockMvc mockMvc;
    @Mock
    private FileService fileService;

    @InjectMocks
    private FileController fileController;

    @BeforeEach
    void setUp() {
        // standalone 装配：控制器 + 全局异常处理器 + Jackson 转换器
        this.mockMvc = MockMvcBuilders.standaloneSetup(fileController)
                .setControllerAdvice(new GlobalExceptionHandler())
                .setMessageConverters(new MappingJackson2HttpMessageConverter(objectMapper))
                .build();
    }

    // ==================== 正向：PDF / Excel / Word ====================

    /**
     * 上传 PDF → 200, fileType=PDF, status=UPLOADED
     */
    @Test
    void uploadPdf_shouldReturnUploaded() throws Exception {
        MockMultipartFile file = new MockMultipartFile(
                "file", "项目申报书.pdf", MediaType.APPLICATION_PDF_VALUE, "fake-pdf".getBytes());

        when(fileService.upload(any(MultipartFile.class))).thenReturn(buildVO(FileType.PDF, "项目申报书.pdf"));

        mockMvc.perform(multipart("/file/upload").file(file))
                .andDo(print())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.fileId").value(FILE_ID.intValue()))
                .andExpect(jsonPath("$.data.fileName").value("项目申报书.pdf"))
                .andExpect(jsonPath("$.data.fileType").value("PDF"))
                .andExpect(jsonPath("$.data.status").value("UPLOADED"));

        verify(fileService).upload(any(MultipartFile.class));
    }

    /**
     * 上传 Excel(xlsx) → 200, fileType=EXCEL
     */
    @Test
    void uploadExcel_shouldReturnUploaded() throws Exception {
        MockMultipartFile file = new MockMultipartFile(
                "file", "data.xlsx",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                "fake-xlsx".getBytes());

        when(fileService.upload(any(MultipartFile.class))).thenReturn(buildVO(FileType.EXCEL, "data.xlsx"));

        mockMvc.perform(multipart("/file/upload").file(file))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.fileType").value("EXCEL"))
                .andExpect(jsonPath("$.data.filePath").exists());

        verify(fileService).upload(any(MultipartFile.class));
    }

    /**
     * 上传 Word(docx) → 200, fileType=WORD
     */
    @Test
    void uploadWord_shouldReturnUploaded() throws Exception {
        MockMultipartFile file = new MockMultipartFile(
                "file", "report.docx",
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                "fake-docx".getBytes());

        when(fileService.upload(any(MultipartFile.class))).thenReturn(buildVO(FileType.WORD, "report.docx"));

        mockMvc.perform(multipart("/file/upload").file(file))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.fileType").value("WORD"))
                .andExpect(jsonPath("$.data.fileName").value("report.docx"));

        verify(fileService).upload(any(MultipartFile.class));
    }

    // ==================== 异常路径 ====================

    /**
     * 不支持的文件类型(.txt) → service 抛 FILE_TYPE_NOT_SUPPORT → 2002
     */
    @Test
    void uploadUnsupportedType_shouldReturn2002() throws Exception {
        MockMultipartFile file = new MockMultipartFile(
                "file", "notes.txt", MediaType.TEXT_PLAIN_VALUE, "hello".getBytes());

        when(fileService.upload(any(MultipartFile.class)))
                .thenThrow(new BusinessException(ResultCode.FILE_TYPE_NOT_SUPPORT, "不支持的文件类型: txt"));

        mockMvc.perform(multipart("/file/upload").file(file))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(2002))
                .andExpect(jsonPath("$.message").exists());

        verify(fileService).upload(any(MultipartFile.class));
    }

    /**
     * 空文件 → service 抛 FILE_UPLOAD_ERROR → 2003
     */
    @Test
    void uploadEmptyFile_shouldReturn2003() throws Exception {
        MockMultipartFile file = new MockMultipartFile(
                "file", "empty.pdf", MediaType.APPLICATION_PDF_VALUE, new byte[0]);

        when(fileService.upload(any(MultipartFile.class)))
                .thenThrow(new BusinessException(ResultCode.FILE_UPLOAD_ERROR, "上传文件为空"));

        mockMvc.perform(multipart("/file/upload").file(file))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(2003))
                .andExpect(jsonPath("$.message").exists());

        verify(fileService).upload(any(MultipartFile.class));
    }

    // ==================== 测试数据构造 ====================

    private FileUploadVO buildVO(FileType type, String fileName) {
        FileUploadVO vo = new FileUploadVO();
        vo.setFileId(FILE_ID);
        vo.setFileName(fileName);
        vo.setFileType(type);
        vo.setFilePath("2026/07/abc123." + type.name().toLowerCase());
        vo.setStatus(FileStatus.UPLOADED);
        vo.setCreateTime(LocalDateTime.now());
        return vo;
    }
}
