package com.aifp.aiagent.service.impl;

import com.aifp.aiagent.dto.FormFieldVO;
import com.aifp.aiagent.dto.FormVO;
import com.aifp.aiagent.entity.enums.FieldType;
import com.aifp.aiagent.entity.enums.FileStatus;
import com.aifp.aiagent.exception.BusinessException;
import com.aifp.aiagent.rag.DocumentIngestionService;
import com.aifp.aiagent.rag.ExtractionPromptBuilder;
import com.aifp.aiagent.rag.FieldQueryGenerator;
import com.aifp.aiagent.rag.VectorStoreService;
import com.aifp.aiagent.service.FileService;
import com.aifp.aiagent.service.FormService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.document.Document;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link FieldExtractorServiceImpl} 测试
 * <p>
 * 离线单测：FieldQueryGenerator/ExtractionPromptBuilder 用真实实例，其余依赖 mock。
 * 验证正常抽取、空字段(5004)、非法 JSON(3002)。
 *
 * @author Tang_tzb
 */
@ExtendWith(MockitoExtension.class)
class FieldExtractorServiceImplTest {

    private static final Long FORM_ID = 1785508135L;
    private static final Long FILE_ID = 1785800001L;

    @Mock
    private DocumentIngestionService ingestionService;
    @Mock
    private FormService formService;
    @Mock
    private FileService fileService;
    @Mock
    private VectorStoreService vectorStoreService;
    @Mock
    private ChatModel chatModel;

    private FieldExtractorServiceImpl extractor;

    @BeforeEach
    void setUp() {
        // 真实辅助组件 + 真实 ObjectMapper，验证 Prompt 构建与 JSON 解析真实链路
        extractor = new FieldExtractorServiceImpl(
                ingestionService, formService, fileService, vectorStoreService,
                new FieldQueryGenerator(), new ExtractionPromptBuilder(),
                chatModel, new ObjectMapper());
        ReflectionTestUtils.setField(extractor, "topK", 5);
    }

    @Test
    void extract_normalFlow_returnsFieldMap() {
        when(formService.getFormById(FORM_ID)).thenReturn(buildForm(2));
        when(vectorStoreService.search(anyString(), anyInt(), anyString()))
                .thenReturn(List.of(new Document("项目名称为智慧校园，投资金额500万元。")));
        when(chatModel.call(any(Prompt.class)))
                .thenReturn(chatResponse("{\"projectName\":\"智慧校园\",\"amount\":5000000}"));

        Map<String, Object> result = extractor.extract(FORM_ID, FILE_ID);

        assertThat(result).containsEntry("projectName", "智慧校园");
        assertThat(result).containsEntry("amount", 5000000);
        verify(ingestionService).ingest(FILE_ID);
        verify(fileService).updateStatus(FILE_ID, FileStatus.EXTRACTING);
        verify(fileService).updateStatus(FILE_ID, FileStatus.SUCCESS);
    }

    @Test
    void extract_emptyFields_throws5004() {
        when(formService.getFormById(FORM_ID)).thenReturn(buildForm(0));

        assertThatThrownBy(() -> extractor.extract(FORM_ID, FILE_ID))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getCode()).isEqualTo(5004));
    }

    @Test
    void extract_invalidJson_throws3002() {
        when(formService.getFormById(FORM_ID)).thenReturn(buildForm(1));
        when(vectorStoreService.search(anyString(), anyInt(), anyString()))
                .thenReturn(List.of(new Document("片段内容")));
        when(chatModel.call(any(Prompt.class)))
                .thenReturn(chatResponse("这不是合法 JSON"));

        assertThatThrownBy(() -> extractor.extract(FORM_ID, FILE_ID))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getCode()).isEqualTo(3002));
    }

    @Test
    void extract_noChunks_throws4002() {
        when(formService.getFormById(FORM_ID)).thenReturn(buildForm(1));
        when(vectorStoreService.search(anyString(), anyInt(), anyString()))
                .thenReturn(List.of());

        assertThatThrownBy(() -> extractor.extract(FORM_ID, FILE_ID))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getCode()).isEqualTo(4002));
    }

    // ==================== 测试数据 ====================

    private ChatResponse chatResponse(String content) {
        return new ChatResponse(List.of(new Generation(new AssistantMessage(content))));
    }

    private FormVO buildForm(int fieldCount) {
        FormVO vo = new FormVO();
        vo.setFormId(FORM_ID);
        vo.setFormName("项目申报表");
        if (fieldCount == 0) {
            vo.setFields(List.of());
            return vo;
        }
        FormFieldVO f1 = new FormFieldVO();
        f1.setFieldId(1L);
        f1.setFieldName("项目名称");
        f1.setFieldCode("projectName");
        f1.setFieldType(FieldType.STRING);
        f1.setRequired(true);
        f1.setSort(1);
        if (fieldCount == 1) {
            vo.setFields(List.of(f1));
            return vo;
        }
        FormFieldVO f2 = new FormFieldVO();
        f2.setFieldId(2L);
        f2.setFieldName("投资金额");
        f2.setFieldCode("amount");
        f2.setFieldType(FieldType.DECIMAL);
        f2.setRequired(true);
        f2.setSort(2);
        vo.setFields(List.of(f1, f2));
        return vo;
    }
}
