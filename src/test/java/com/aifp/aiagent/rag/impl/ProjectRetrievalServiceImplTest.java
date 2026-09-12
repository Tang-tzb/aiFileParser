package com.aifp.aiagent.rag.impl;

import com.aifp.aiagent.common.ResultCode;
import com.aifp.aiagent.entity.Project;
import com.aifp.aiagent.exception.BusinessException;
import com.aifp.aiagent.rag.ProjectRetrievalService;
import com.aifp.aiagent.rag.VectorStoreService;
import com.aifp.aiagent.service.FileService;
import com.aifp.aiagent.service.ProjectAccessService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.document.Document;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * {@link ProjectRetrievalServiceImpl} 测试（离线，Phase 6）
 * <p>
 * 约束 11：ArgumentCaptor 精确验证实际 filterExpression 字符串，覆盖
 * 单项目有文件 / 单项目无文件 / 跨项目 / 历史文件兜底 / 重复 fileId / query 空白 /
 * 权限失败 / 项目不存在；另验证约束 2（权限先于文件查询）、约束 5（distinct+sort）、
 * 约束 6（参数防御）、约束 7/8（topK 原样透传、结果原样返回）。
 *
 * @author Tang_tzb
 */
@ExtendWith(MockitoExtension.class)
class ProjectRetrievalServiceImplTest {

    private static final Long PROJECT_ID = 1785900001L;
    private static final Long PROJECT_ID_2 = 1785900002L;
    private static final Long FILE_ID_1 = 1785800001L;
    private static final Long FILE_ID_2 = 1785800002L;
    private static final Long FILE_ID_3 = 1785800003L;
    private static final String QUERY = "项目投资金额";
    private static final int TOP_K = 5;

    @Mock
    private VectorStoreService vectorStoreService;
    @Mock
    private FileService fileService;
    @Mock
    private ProjectAccessService projectAccessService;
    @Mock
    private com.aifp.aiagent.repository.ProjectMapper projectMapper;

    private ProjectRetrievalService retrievalService;

    @BeforeEach
    void setUp() {
        retrievalService = new ProjectRetrievalServiceImpl(
                vectorStoreService, fileService, projectAccessService, projectMapper);
    }

    // ==================== 过滤表达式精确验证（约束 11） ====================

    /**
     * 单项目有文件：projectId 直查 + fileId 兜底，OR 连接括号分组（约束 1 固定语义）
     */
    @Test
    void retrieve_singleProjectWithFiles_exactFilterExpression() {
        guardPasses(PROJECT_ID);
        when(fileService.listFileIdsByProject(PROJECT_ID)).thenReturn(List.of(FILE_ID_1, FILE_ID_2));
        stubSearch(List.of(new Document("命中")));

        retrievalService.retrieve(PROJECT_ID, QUERY, TOP_K);

        assertThat(capturedFilter()).isEqualTo(
                "(projectId == '1785900001' || fileId in ['1785800001','1785800002'])");
    }

    /**
     * 历史文件兜底语义（约束 3）：项目文件若先于 Phase 5 入库（chunk 无 projectId metadata），
     * 只能经 fileId in 兜底子句命中——兜底子句存在即历史 chunk 可见；兜底列表实时来自
     * file_record（约束 4），不做任何缓存。
     */
    @Test
    void retrieve_historicalFile_fallbackClausePresent() {
        guardPasses(PROJECT_ID);
        // 该文件入库早于 Phase 5：其 chunk 无 projectId metadata，仅 fileId 可命中
        when(fileService.listFileIdsByProject(PROJECT_ID)).thenReturn(List.of(FILE_ID_1));

        retrievalService.retrieve(PROJECT_ID, QUERY, TOP_K);

        String filter = capturedFilter();
        assertThat(filter).isEqualTo(
                "(projectId == '1785900001' || fileId in ['1785800001'])");
        assertThat(filter).contains("|| fileId in [").contains("'1785800001'");
    }

    /**
     * 单项目无文件：空 IN 列表非法，省略 OR 子句（非"优化删除兜底"，无兜底对象）
     */
    @Test
    void retrieve_singleProjectWithoutFiles_projectClauseOnly() {
        guardPasses(PROJECT_ID);
        when(fileService.listFileIdsByProject(PROJECT_ID)).thenReturn(List.of());

        retrievalService.retrieve(PROJECT_ID, QUERY, TOP_K);

        assertThat(capturedFilter()).isEqualTo("projectId == '1785900001'");
    }

    /**
     * 跨项目（约束 1）：projectId in [...] + 兜底并集；
     * 入参乱序 → 输出排序（约束 5 Filter 确定性）
     */
    @Test
    void retrieve_multiProject_exactFilterExpression() {
        guardPasses(PROJECT_ID);
        guardPasses(PROJECT_ID_2);
        when(fileService.listFileIdsByProject(PROJECT_ID)).thenReturn(List.of(FILE_ID_1));
        when(fileService.listFileIdsByProject(PROJECT_ID_2)).thenReturn(List.of(FILE_ID_3));

        // 故意乱序传入
        List<Long> unordered = new ArrayList<>(List.of(PROJECT_ID_2, PROJECT_ID));
        retrievalService.retrieve(unordered, QUERY, TOP_K);

        assertThat(capturedFilter()).isEqualTo(
                "(projectId in ['1785900001','1785900002'] || fileId in ['1785800001','1785800003'])");
    }

    /**
     * 重复 fileId（约束 5）：跨项目兜底并集 distinct + sort，Filter 中只出现一次
     */
    @Test
    void retrieve_duplicateFileIds_dedupedInFilter() {
        guardPasses(PROJECT_ID);
        guardPasses(PROJECT_ID_2);
        // 项目间理论互斥，防御性覆盖：两项目返回重复 fileId
        when(fileService.listFileIdsByProject(PROJECT_ID)).thenReturn(List.of(FILE_ID_2, FILE_ID_1));
        when(fileService.listFileIdsByProject(PROJECT_ID_2)).thenReturn(List.of(FILE_ID_2));

        retrievalService.retrieve(List.of(PROJECT_ID, PROJECT_ID_2), QUERY, TOP_K);

        assertThat(capturedFilter()).isEqualTo(
                "(projectId in ['1785900001','1785900002'] || fileId in ['1785800001','1785800002'])");
    }

    // ==================== 参数防御与行为（约束 6/7/8） ====================

    /**
     * query 空白：直接返回空列表，不调用向量库（也不守门/查文件）
     */
    @Test
    void retrieve_blankQuery_returnsEmptyWithoutSearch() {
        assertThat(retrievalService.retrieve(PROJECT_ID, "  ", TOP_K)).isEmpty();
        assertThat(retrievalService.retrieve(PROJECT_ID, null, TOP_K)).isEmpty();
        assertThat(retrievalService.retrieve(List.of(PROJECT_ID), "", TOP_K)).isEmpty();

        verifyNoInteractions(vectorStoreService, fileService, projectAccessService, projectMapper);
    }

    /**
     * 参数防御（约束 6）：projectId null NPE；projectIds null/empty/含 null IAE，
     * 均不触碰任何依赖
     */
    @Test
    void retrieve_invalidArguments_rejectedBeforeAnyInteraction() {
        assertThatThrownBy(() -> retrievalService.retrieve((Long) null, QUERY, TOP_K))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> retrievalService.retrieve(new ArrayList<>(), QUERY, TOP_K))
                .isInstanceOf(IllegalArgumentException.class);
        // ArrayList 手工装配以允许 null 元素（List.of 拒绝 null，无法测到服务端防御）
        List<Long> withNull = new ArrayList<>();
        withNull.add(PROJECT_ID);
        withNull.add(null);
        assertThatThrownBy(() -> retrievalService.retrieve(withNull, QUERY, TOP_K))
                .isInstanceOf(IllegalArgumentException.class);

        verifyNoInteractions(vectorStoreService, fileService, projectAccessService, projectMapper);
    }

    /**
     * topK 原样透传（约束 7），向量库结果原样返回不复制不排序（约束 8）
     */
    @Test
    void retrieve_passesThroughTopKAndReturnsRawHits() {
        guardPasses(PROJECT_ID);
        when(fileService.listFileIdsByProject(PROJECT_ID)).thenReturn(List.of(FILE_ID_1));
        List<Document> hits = List.of(new Document("d1"), new Document("d2"));
        stubSearch(hits);

        List<Document> result = retrievalService.retrieve(PROJECT_ID, QUERY, 42);

        verify(vectorStoreService).search(eq(QUERY), eq(42), anyString());
        assertThat(result).isSameAs(hits);
    }

    // ==================== 守门（约束 2） ====================

    /**
     * 无权限：FORBIDDEN，且不得发起文件查询/向量检索（防项目资源信息泄露）
     */
    @Test
    void retrieve_accessDenied_throwsBeforeFileQuery() {
        when(projectAccessService.canAccess(PROJECT_ID)).thenReturn(false);

        assertThatThrownBy(() -> retrievalService.retrieve(PROJECT_ID, QUERY, TOP_K))
                .isInstanceOfSatisfying(BusinessException.class, e ->
                        assertThat(e.getCode()).isEqualTo(ResultCode.FORBIDDEN.getCode()));

        verifyNoInteractions(fileService, vectorStoreService);
    }

    /**
     * 项目不存在：PROJECT_NOT_FOUND(6001)，且不得发起文件查询
     */
    @Test
    void retrieve_projectNotFound_throws6001() {
        when(projectAccessService.canAccess(PROJECT_ID)).thenReturn(true);
        when(projectMapper.selectById(PROJECT_ID)).thenReturn(null);

        assertThatThrownBy(() -> retrievalService.retrieve(PROJECT_ID, QUERY, TOP_K))
                .isInstanceOfSatisfying(BusinessException.class, e ->
                        assertThat(e.getCode()).isEqualTo(ResultCode.PROJECT_NOT_FOUND.getCode()));

        verifyNoInteractions(fileService, vectorStoreService);
    }

    /**
     * 跨项目逐项目守门（约束 2）：第二个项目无权限即拒绝，
     * 全部项目守门通过前不查任何项目的文件
     */
    @Test
    void retrieve_multiProject_secondDenied_guardAllBeforeFileQuery() {
        // 第一个项目守门通过（权限+存在性），第二个项目无权限
        guardPasses(PROJECT_ID);
        when(projectAccessService.canAccess(PROJECT_ID_2)).thenReturn(false);

        assertThatThrownBy(() ->
                retrievalService.retrieve(List.of(PROJECT_ID, PROJECT_ID_2), QUERY, TOP_K))
                .isInstanceOfSatisfying(BusinessException.class, e ->
                        assertThat(e.getCode()).isEqualTo(ResultCode.FORBIDDEN.getCode()));

        verifyNoInteractions(fileService, vectorStoreService);
    }

    /**
     * 跨项目项目不存在：6001，不查文件
     */
    @Test
    void retrieve_multiProject_projectMissing_throws6001() {
        when(projectAccessService.canAccess(PROJECT_ID)).thenReturn(true);
        when(projectMapper.selectById(PROJECT_ID)).thenReturn(null);

        assertThatThrownBy(() ->
                retrievalService.retrieve(List.of(PROJECT_ID), QUERY, TOP_K))
                .isInstanceOfSatisfying(BusinessException.class, e ->
                        assertThat(e.getCode()).isEqualTo(ResultCode.PROJECT_NOT_FOUND.getCode()));

        verifyNoInteractions(fileService, vectorStoreService);
    }

    // ==================== 测试辅助 ====================

    private void guardPasses(Long projectId) {
        when(projectAccessService.canAccess(projectId)).thenReturn(true);
        when(projectMapper.selectById(projectId)).thenReturn(new Project());
    }

    private void stubSearch(List<Document> hits) {
        when(vectorStoreService.search(anyString(), anyInt(), anyString())).thenReturn(hits);
    }

    /**
     * 捕获最近一次向量检索的 filterExpression（约束 11：精确字符串断言）
     */
    private String capturedFilter() {
        ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
        verify(vectorStoreService).search(anyString(), anyInt(), captor.capture());
        return captor.getValue();
    }
}
