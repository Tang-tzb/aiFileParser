package com.aifp.aiagent.service.impl;

import com.aifp.aiagent.dto.ExtractionResult;
import com.aifp.aiagent.dto.FileRecordVO;
import com.aifp.aiagent.dto.FormFieldVO;
import com.aifp.aiagent.dto.FormVO;
import com.aifp.aiagent.entity.enums.FieldType;
import com.aifp.aiagent.entity.enums.FileStatus;
import com.aifp.aiagent.exception.BusinessException;
import com.aifp.aiagent.rag.*;
import com.aifp.aiagent.service.FileService;
import com.aifp.aiagent.service.FormService;
import com.aifp.aiagent.service.ProjectFormPersistenceService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.document.Document;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * {@link FieldExtractorServiceImpl} 测试
 * <p>
 * 离线单测：FieldQueryGenerator/ExtractionPromptBuilder/FieldSchemaValidator 用真实实例，
 * 其余依赖 mock。验证正常抽取、Retry 成功、空字段(5004)、非法 JSON(3002)、空切片(4002)。
 *
 * @author Tang_tzb
 */
@ExtendWith(MockitoExtension.class)
class FieldExtractorServiceImplTest {

    private static final Long FORM_ID = 1785508135L;
    private static final Long FILE_ID = 1785800001L;
    private static final Long PROJECT_ID = 1785900001L;

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
    @Mock
    private ProjectFormPersistenceService projectFormPersistenceService;

    private FieldExtractorServiceImpl extractor;

    @BeforeEach
    void setUp() {
        // 真实辅助组件 + 真实 ObjectMapper，验证 Prompt 构建/JSON 解析/Schema 校验真实链路
        extractor = new FieldExtractorServiceImpl(
                ingestionService, formService, fileService, vectorStoreService,
                new FieldQueryGenerator(), new ExtractionPromptBuilder(),
                new FieldSchemaValidator(), chatModel, new ObjectMapper(),
                projectFormPersistenceService);
        ReflectionTestUtils.setField(extractor, "topK", 5);
        ReflectionTestUtils.setField(extractor, "maxAttempts", 2);
    }

    @Test
    void extract_normalFlow_returnsResultNoErrors() {
        stubFileRecord(FileStatus.UPLOADED);
        when(formService.getFormById(FORM_ID)).thenReturn(buildForm(2));
        when(vectorStoreService.search(anyString(), anyInt(), anyString()))
                .thenReturn(List.of(new Document("项目名称为智慧校园，投资金额500万元。")));
        when(chatModel.call(any(Prompt.class)))
                .thenReturn(chatResponse("{\"projectName\":\"智慧校园\",\"amount\":5000000}"));

        ExtractionResult result = extractor.extract(FORM_ID, FILE_ID);

        assertThat(result.getErrors()).isEmpty();
        assertThat(result.getAttemptsUsed()).isEqualTo(1);
        assertThat(result.getValues()).containsEntry("projectName", "智慧校园");
        assertThat((BigDecimal) result.getValues().get("amount"))
                .isEqualByComparingTo(new BigDecimal("5000000"));
        verify(fileService).updateStatus(FILE_ID, FileStatus.EXTRACTING);
        verify(fileService).updateStatus(FILE_ID, FileStatus.SUCCESS);
        // 阶段 13 固化：fileId 过滤表达式必须保持（跨文件污染防线）
        verify(vectorStoreService, times(2))
                .search(anyString(), anyInt(), eq("fileId == '" + FILE_ID + "'"));
    }

    /**
     * Retry 成功路径：首次返回 amount="金额待定"(类型错误) → 反馈 → 二次返回数字 → 成功。
     */
    @Test
    void extract_typeErrorThenRetry_successOnSecondAttempt() {
        stubFileRecord(FileStatus.UPLOADED);
        when(formService.getFormById(FORM_ID)).thenReturn(buildForm(2));
        when(vectorStoreService.search(anyString(), anyInt(), anyString()))
                .thenReturn(List.of(new Document("项目名称为智慧校园，投资金额500万元。")));
        when(chatModel.call(any(Prompt.class)))
                .thenReturn(chatResponse("{\"projectName\":\"智慧校园\",\"amount\":\"金额待定\"}"))
                .thenReturn(chatResponse("{\"projectName\":\"智慧校园\",\"amount\":5000000}"));

        ExtractionResult result = extractor.extract(FORM_ID, FILE_ID);

        assertThat(result.getAttemptsUsed()).isEqualTo(2);
        assertThat(result.getErrors()).isEmpty();
        assertThat(result.getValues()).containsEntry("projectName", "智慧校园");
        verify(chatModel, times(2)).call(any(Prompt.class));
    }

    @Test
    void extract_emptyFields_throws5004() {
        stubFileRecord(FileStatus.UPLOADED);
        when(formService.getFormById(FORM_ID)).thenReturn(buildForm(0));

        assertThatThrownBy(() -> extractor.extract(FORM_ID, FILE_ID))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getCode()).isEqualTo(5004));
    }

    @Test
    void extract_invalidJson_throws3002() {
        stubFileRecord(FileStatus.UPLOADED);
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
    void extract_noChunks_throws4002AndMarkFailed() {
        stubFileRecord(FileStatus.UPLOADED);
        when(formService.getFormById(FORM_ID)).thenReturn(buildForm(1));
        when(vectorStoreService.search(anyString(), anyInt(), anyString()))
                .thenReturn(List.of());

        assertThatThrownBy(() -> extractor.extract(FORM_ID, FILE_ID))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getCode()).isEqualTo(4002));
        // 阶段 13：检索为空失败必须落 FAILED，不得滞留 EXTRACTING
        verify(fileService).updateStatus(FILE_ID, FileStatus.EXTRACTING);
        verify(fileService).updateStatus(FILE_ID, FileStatus.FAILED);
    }

    /**
     * 阶段 13：AI 调用失败必须落 FAILED 并原样传播异常（覆盖 /fill 直调路径）。
     */
    @Test
    void extract_aiFailure_markFailedAndRethrow() {
        stubFileRecord(FileStatus.UPLOADED);
        when(formService.getFormById(FORM_ID)).thenReturn(buildForm(1));
        when(vectorStoreService.search(anyString(), anyInt(), anyString()))
                .thenReturn(List.of(new Document("片段内容")));
        when(chatModel.call(any(Prompt.class)))
                .thenReturn(chatResponse("这不是合法 JSON"));

        assertThatThrownBy(() -> extractor.extract(FORM_ID, FILE_ID))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getCode()).isEqualTo(3002));

        verify(fileService).updateStatus(FILE_ID, FileStatus.EXTRACTING);
        verify(fileService).updateStatus(FILE_ID, FileStatus.FAILED);
        verify(fileService, never()).updateStatus(FILE_ID, FileStatus.SUCCESS);
    }

    /**
     * 阶段 13 终态封闭：SUCCESS 文件重新抽取（换表单）合法，但状态零写入——
     * 不产生 SUCCESS → EXTRACTING / SUCCESS → SUCCESS 非法跳转；结果正常返回。
     */
    @Test
    void extract_alreadySuccess_skipsStatusWrites() {
        stubFileRecord(FileStatus.SUCCESS);
        when(formService.getFormById(FORM_ID)).thenReturn(buildForm(1));
        when(vectorStoreService.search(anyString(), anyInt(), anyString()))
                .thenReturn(List.of(new Document("项目名称为智慧校园")));
        when(chatModel.call(any(Prompt.class)))
                .thenReturn(chatResponse("{\"projectName\":\"智慧校园\"}"));

        ExtractionResult result = extractor.extract(FORM_ID, FILE_ID);

        assertThat(result.getErrors()).isEmpty();
        assertThat(result.getValues()).containsEntry("projectName", "智慧校园");
        verify(fileService, never()).updateStatus(eq(FILE_ID), any());
    }

    // ==================== Phase 4：项目持久化与溯源 ====================

    /**
     * 项目链路：projectId 非空时持久化先于 SUCCESS（业务闭环，约束 1/补充 1），
     * 且 persistence 收到的 fields 与抽取一致。
     */
    @Test
    void extract_withProjectId_persistsBeforeSuccess() {
        stubFileRecord(FileStatus.UPLOADED, PROJECT_ID);
        when(formService.getFormById(FORM_ID)).thenReturn(buildForm(2));
        when(vectorStoreService.search(anyString(), anyInt(), anyString()))
                .thenReturn(List.of(new Document("项目名称为智慧校园，投资金额500万元。")));
        when(chatModel.call(any(Prompt.class)))
                .thenReturn(chatResponse("{\"projectName\":\"智慧校园\",\"amount\":5000000}"));

        ExtractionResult result = extractor.extract(PROJECT_ID, FORM_ID, FILE_ID);

        assertThat(result.getErrors()).isEmpty();
        InOrder inOrder = inOrder(projectFormPersistenceService, fileService);
        inOrder.verify(projectFormPersistenceService).persistExtraction(
                eq(PROJECT_ID), eq(FORM_ID), eq(FILE_ID), anyList(), eq(result));
        inOrder.verify(fileService).updateStatus(FILE_ID, FileStatus.SUCCESS);
        // 持久化失败链路由 markFailed 兜底：SUCCESS 不先于持久化发生
        verify(projectFormPersistenceService, times(1)).persistExtraction(
                any(), any(), any(), anyList(), any());
    }

    /**
     * 历史 API（需求 §九）：extract(formId, fileId) 内部按 fileId 解析 projectId，
     * 文件归属项目 → 持久化收到解析出的 projectId。
     */
    @Test
    void extract_legacy_resolvesProjectIdFromFile() {
        stubFileRecord(FileStatus.UPLOADED, PROJECT_ID);
        when(formService.getFormById(FORM_ID)).thenReturn(buildForm(1));
        when(vectorStoreService.search(anyString(), anyInt(), anyString()))
                .thenReturn(List.of(new Document("项目名称为智慧校园")));
        when(chatModel.call(any(Prompt.class)))
                .thenReturn(chatResponse("{\"projectName\":\"智慧校园\"}"));

        extractor.extract(FORM_ID, FILE_ID);

        verify(projectFormPersistenceService).persistExtraction(
                eq(PROJECT_ID), eq(FORM_ID), eq(FILE_ID), anyList(), any());
    }

    /**
     * 历史兼容（补充约束 5）：projectId=null 完全跳过项目域持久化，抽取行为不变。
     */
    @Test
    void extract_nullProjectId_skipsPersistence() {
        stubFileRecord(FileStatus.UPLOADED, null);
        when(formService.getFormById(FORM_ID)).thenReturn(buildForm(1));
        when(vectorStoreService.search(anyString(), anyInt(), anyString()))
                .thenReturn(List.of(new Document("项目名称为智慧校园")));
        when(chatModel.call(any(Prompt.class)))
                .thenReturn(chatResponse("{\"projectName\":\"智慧校园\"}"));

        ExtractionResult result = extractor.extract((Long) null, FORM_ID, FILE_ID);

        assertThat(result.getErrors()).isEmpty();
        verify(projectFormPersistenceService, never()).persistExtraction(
                any(), any(), any(), anyList(), any());
        verify(fileService).updateStatus(FILE_ID, FileStatus.SUCCESS);
    }

    /**
     * 跨项目错绑防护：显式 projectId 与文件归属不一致 → 6004，不持久化不写 SUCCESS。
     */
    @Test
    void extract_projectMismatch_throws6004() {
        stubFileRecord(FileStatus.UPLOADED, PROJECT_ID);

        assertThatThrownBy(() -> extractor.extract(9999L, FORM_ID, FILE_ID))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getCode()).isEqualTo(6004));

        verify(projectFormPersistenceService, never()).persistExtraction(
                any(), any(), any(), anyList(), any());
        verify(fileService, never()).updateStatus(FILE_ID, FileStatus.SUCCESS);
    }

    /**
     * 生命周期（补充约束 9-①）：抽取失败 → 持久化零调用 → 无新版本产生。
     */
    @Test
    void extract_failure_noPersistenceNoVersionBump() {
        stubFileRecord(FileStatus.UPLOADED, PROJECT_ID);
        when(formService.getFormById(FORM_ID)).thenReturn(buildForm(1));
        when(vectorStoreService.search(anyString(), anyInt(), anyString()))
                .thenReturn(List.of(new Document("片段内容")));
        when(chatModel.call(any(Prompt.class)))
                .thenReturn(chatResponse("这不是合法 JSON"));

        assertThatThrownBy(() -> extractor.extract(PROJECT_ID, FORM_ID, FILE_ID))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getCode()).isEqualTo(3002));

        verify(projectFormPersistenceService, never()).persistExtraction(
                any(), any(), any(), anyList(), any());
        verify(fileService).updateStatus(FILE_ID, FileStatus.FAILED);
    }

    /**
     * SUCCESS 终态文件重抽取：状态零写入，但项目域字段值仍刷新（同源替换）。
     */
    @Test
    void extract_alreadySuccess_withProjectId_persistsWithoutStatusWrites() {
        stubFileRecord(FileStatus.SUCCESS, PROJECT_ID);
        when(formService.getFormById(FORM_ID)).thenReturn(buildForm(1));
        when(vectorStoreService.search(anyString(), anyInt(), anyString()))
                .thenReturn(List.of(new Document("项目名称为智慧校园")));
        when(chatModel.call(any(Prompt.class)))
                .thenReturn(chatResponse("{\"projectName\":\"智慧校园\"}"));

        extractor.extract(PROJECT_ID, FORM_ID, FILE_ID);

        verify(fileService, never()).updateStatus(eq(FILE_ID), any());
        verify(projectFormPersistenceService).persistExtraction(
                eq(PROJECT_ID), eq(FORM_ID), eq(FILE_ID), anyList(), any());
    }

    /**
     * 引用式溯源：LLM 返回 sources 旁路键 → sourceChunkId=Document.getId()、
     * sourcePage=被引用 Chunk metadata pageStart；未知编号忽略不猜（补充约束 3）。
     */
    @Test
    void extract_withSources_resolvesChunkIdAndPage() {
        stubFileRecord(FileStatus.UPLOADED, null);
        Document cited = new Document("项目名称为智慧校园",
                Map.of("fileId", FILE_ID, "pageStart", 3));
        Document other = new Document("无关片段", Map.of("fileId", FILE_ID, "pageStart", 9));
        when(formService.getFormById(FORM_ID)).thenReturn(buildForm(2));
        when(vectorStoreService.search(anyString(), anyInt(), anyString()))
                .thenReturn(List.of(cited, other));
        when(chatModel.call(any(Prompt.class)))
                .thenReturn(chatResponse("{\"projectName\":\"智慧校园\",\"amount\":5000000,"
                        + "\"sources\":{\"projectName\":\"C1\",\"amount\":\"C9\"}}"));

        ExtractionResult result = extractor.extract((Long) null, FORM_ID, FILE_ID);

        // C1 命中 → 引用事实；C9 未知编号 → 忽略（禁止按下标/相似度猜来源）
        assertThat(result.getSources()).containsOnlyKeys("projectName");
        assertThat(result.getSources()).containsEntry("projectName", cited.getId());
        assertThat(result.getSourcePages()).containsEntry("projectName", 3);
    }

    /**
     * 软依赖（补充约束 6）：LLM 未返回 sources → 抽取仍成功，溯源列为空。
     */
    @Test
    void extract_withoutSources_stillSuccessWithEmptySources() {
        stubFileRecord(FileStatus.UPLOADED, null);
        when(formService.getFormById(FORM_ID)).thenReturn(buildForm(1));
        when(vectorStoreService.search(anyString(), anyInt(), anyString()))
                .thenReturn(List.of(new Document("项目名称为智慧校园")));
        when(chatModel.call(any(Prompt.class)))
                .thenReturn(chatResponse("{\"projectName\":\"智慧校园\"}"));

        ExtractionResult result = extractor.extract((Long) null, FORM_ID, FILE_ID);

        assertThat(result.getErrors()).isEmpty();
        assertThat(result.getValues()).containsEntry("projectName", "智慧校园");
        assertThat(result.getSources()).isEmpty();
        assertThat(result.getSourcePages()).isEmpty();
    }

    /**
     * 原始值快照：rawValues 保留 coerce 前形态（"500万"），values 为转换后数值。
     * 注：validator 既有语义仅剥尾部「万/亿」单位（"元" 不支持），本测试不改动该契约。
     */
    @Test
    void extract_keepsRawValuesBeforeCoercion() {
        stubFileRecord(FileStatus.UPLOADED, null);
        when(formService.getFormById(FORM_ID)).thenReturn(buildForm(2));
        when(vectorStoreService.search(anyString(), anyInt(), anyString()))
                .thenReturn(List.of(new Document("项目名称为智慧校园，投资金额500万元。")));
        when(chatModel.call(any(Prompt.class)))
                .thenReturn(chatResponse("{\"projectName\":\"智慧校园\",\"amount\":\"500万\"}"));

        ExtractionResult result = extractor.extract((Long) null, FORM_ID, FILE_ID);

        assertThat((BigDecimal) result.getValues().get("amount"))
                .isEqualByComparingTo(new BigDecimal("5000000"));
        assertThat(result.getRawValues()).containsEntry("amount", "500万");
        assertThat(result.getRawValues()).containsEntry("projectName", "智慧校园");
    }

    // ==================== 测试数据 ====================

    /**
     * 桩：extract 内 SUCCESS 预检读取文件状态（projectId 默认未归属）。
     */
    private void stubFileRecord(FileStatus status) {
        stubFileRecord(status, null);
    }

    /**
     * 桩：extract 内 SUCCESS 预检 + projectId 解析/一致性校验读取文件记录。
     */
    private void stubFileRecord(FileStatus status, Long projectId) {
        FileRecordVO vo = new FileRecordVO();
        vo.setFileId(FILE_ID);
        vo.setFileName("项目申报书.pdf");
        vo.setStatus(status);
        vo.setProjectId(projectId);
        when(fileService.getById(FILE_ID)).thenReturn(vo);
    }

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
