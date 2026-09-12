package com.aifp.aiagent.service.impl;

import com.aifp.aiagent.common.ResultCode;
import com.aifp.aiagent.dto.PageQuery;
import com.aifp.aiagent.dto.PageResult;
import com.aifp.aiagent.entity.FileRecord;
import com.aifp.aiagent.entity.Project;
import com.aifp.aiagent.entity.enums.FileStatus;
import com.aifp.aiagent.entity.enums.FileType;
import com.aifp.aiagent.exception.BusinessException;
import com.aifp.aiagent.repository.FileRecordMapper;
import com.aifp.aiagent.repository.ProjectMapper;
import com.aifp.aiagent.service.ProjectAccessService;
import com.aifp.aiagent.service.storage.FileStorageService;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * {@link FileServiceImpl} 测试
 * <p>
 * 覆盖两组能力：① {@code updateStatus} 状态机唯一写入口（阶段 13）：合法迁移落库、
 * 同态写幂等、非法迁移拒绝；② 项目关联（Phase 2）：上传可选归属项目、
 * 关联/解除关联的归属校验（6003 已绑定其他项目 / 6004 不属于该项目）。
 * Mapper/Storage/ProjectAccessService 均为 Mockito 模拟，完全离线。
 *
 * @author Tang_tzb
 */
@ExtendWith(MockitoExtension.class)
class FileServiceImplTest {

    private static final Long FILE_ID = 1785800001L;
    private static final Long PROJECT_ID = 1785900001L;

    /**
     * 离线初始化 MyBatis-Plus 实体元数据：LambdaQueryWrapper 的列解析需要实体
     * TableInfo（生产环境由 Mapper 注册自动完成，测试需手动初始化）。
     */
    @BeforeAll
    static void initEntityMetadata() {
        MapperBuilderAssistant assistant =
                new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, FileRecord.class);
    }

    @Mock
    private FileRecordMapper fileRecordMapper;
    @Mock
    private FileStorageService fileStorageService;
    @Mock
    private ProjectMapper projectMapper;
    @Mock
    private ProjectAccessService projectAccessService;

    private FileServiceImpl fileService;

    @BeforeEach
    void setUp() {
        fileService = new FileServiceImpl(
                fileRecordMapper, fileStorageService, projectMapper, projectAccessService);
    }

    /**
     * 合法迁移（主链路逐跳 + FAILED 重试回路）：正常落库目标状态。
     */
    @Test
    void updateStatus_legalTransitions_writes() {
        assertLegalWrite(FileStatus.UPLOADED, FileStatus.PARSING);
        assertLegalWrite(FileStatus.PARSING, FileStatus.VECTORING);
        assertLegalWrite(FileStatus.VECTORING, FileStatus.EXTRACTING);
        assertLegalWrite(FileStatus.EXTRACTING, FileStatus.SUCCESS);
        assertLegalWrite(FileStatus.EXTRACTING, FileStatus.FAILED);
        assertLegalWrite(FileStatus.PARSING, FileStatus.FAILED);
        assertLegalWrite(FileStatus.VECTORING, FileStatus.FAILED);
        assertLegalWrite(FileStatus.FAILED, FileStatus.PARSING);
    }

    /**
     * 同态写幂等 no-op：不抛错、不落库（markFailed 双写 FAILED→FAILED 场景）。
     */
    @Test
    void updateStatus_sameState_noOpWithoutWrite() {
        stubRecord(FileStatus.FAILED);

        fileService.updateStatus(FILE_ID, FileStatus.FAILED);

        verify(fileRecordMapper, never()).updateById(any(FileRecord.class));
    }

    /**
     * 用户点名非法跳转（阶段 13 修复目标）：SUCCESS → EXTRACTING 必须拒绝且不落库。
     */
    @Test
    void updateStatus_successToExtracting_rejectedWithoutWrite() {
        stubRecord(FileStatus.SUCCESS);

        assertThatThrownBy(() -> fileService.updateStatus(FILE_ID, FileStatus.EXTRACTING))
                .isInstanceOfSatisfying(BusinessException.class, e ->
                        assertThat(e.getCode()).isEqualTo(ResultCode.FILE_STATUS_ILLEGAL_TRANSITION.getCode()));

        verify(fileRecordMapper, never()).updateById(any(FileRecord.class));
    }

    /**
     * 用户点名非法跳转：SUCCESS → PARSING、VECTORING → PARSING 必须拒绝。
     */
    @Test
    void updateStatus_backToParsing_rejected() {
        assertThatIllegal(FileStatus.SUCCESS, FileStatus.PARSING);
        assertThatIllegal(FileStatus.VECTORING, FileStatus.PARSING);
        // 终态 SUCCESS 其他出边一并拒绝
        assertThatIllegal(FileStatus.SUCCESS, FileStatus.VECTORING);
        assertThatIllegal(FileStatus.SUCCESS, FileStatus.FAILED);
    }

    /**
     * 文件不存在：抛 FILE_NOT_FOUND。
     */
    @Test
    void updateStatus_fileNotFound_throws() {
        when(fileRecordMapper.selectById(FILE_ID)).thenReturn(null);

        assertThatThrownBy(() -> fileService.updateStatus(FILE_ID, FileStatus.PARSING))
                .isInstanceOfSatisfying(BusinessException.class, e ->
                        assertThat(e.getCode()).isEqualTo(ResultCode.FILE_NOT_FOUND.getCode()));
    }

    // ==================== 项目关联（Phase 2） ====================

    /**
     * 上传不带 projectId：保持历史行为，不触碰项目校验，归属为 null
     */
    @Test
    void upload_withoutProjectId_keepsLegacyBehavior() {
        stubStorageAndInsert("legacy.pdf");
        fileService.upload(pdfFile(), null);

        ArgumentCaptor<FileRecord> captor = ArgumentCaptor.forClass(FileRecord.class);
        verify(fileRecordMapper).insert(captor.capture());
        assertThat(captor.getValue().getProjectId()).isNull();
        verifyNoInteractions(projectMapper, projectAccessService);
    }

    /**
     * 上传带 projectId：权限+存在校验通过后，记录归属项目
     */
    @Test
    void upload_withProjectId_setsOwnership() {
        stubStorageAndInsert("owned.pdf");
        when(projectAccessService.canAccess(PROJECT_ID)).thenReturn(true);
        when(projectMapper.selectById(PROJECT_ID)).thenReturn(new Project());

        fileService.upload(pdfFile(), PROJECT_ID);

        ArgumentCaptor<FileRecord> captor = ArgumentCaptor.forClass(FileRecord.class);
        verify(fileRecordMapper).insert(captor.capture());
        assertThat(captor.getValue().getProjectId()).isEqualTo(PROJECT_ID);
    }

    /**
     * 上传带不存在的 projectId：抛 6001，不落库
     */
    @Test
    void upload_projectNotFound_throwsWithoutInsert() {
        when(projectAccessService.canAccess(PROJECT_ID)).thenReturn(true);
        when(projectMapper.selectById(PROJECT_ID)).thenReturn(null);

        assertThatThrownBy(() -> fileService.upload(pdfFile(), PROJECT_ID))
                .isInstanceOfSatisfying(BusinessException.class, e ->
                        assertThat(e.getCode()).isEqualTo(ResultCode.PROJECT_NOT_FOUND.getCode()));

        verify(fileRecordMapper, never()).insert(any(FileRecord.class));
    }

    /**
     * 上传带无权限 projectId：抛 FORBIDDEN，不查项目不落库
     */
    @Test
    void upload_accessDenied_throwsForbidden() {
        when(projectAccessService.canAccess(PROJECT_ID)).thenReturn(false);

        assertThatThrownBy(() -> fileService.upload(pdfFile(), PROJECT_ID))
                .isInstanceOfSatisfying(BusinessException.class, e ->
                        assertThat(e.getCode()).isEqualTo(ResultCode.FORBIDDEN.getCode()));

        verifyNoInteractions(projectMapper);
        verify(fileRecordMapper, never()).insert(any(FileRecord.class));
    }

    /**
     * 关联成功：未归属文件更新 project_id
     */
    @Test
    void associate_success_updatesOwnership() {
        FileRecord record = new FileRecord();
        record.setId(FILE_ID);
        record.setProjectId(null);
        when(fileRecordMapper.selectById(FILE_ID)).thenReturn(record);

        fileService.associateToProject(PROJECT_ID, FILE_ID);

        ArgumentCaptor<FileRecord> captor = ArgumentCaptor.forClass(FileRecord.class);
        verify(fileRecordMapper).updateById(captor.capture());
        assertThat(captor.getValue().getProjectId()).isEqualTo(PROJECT_ID);
    }

    /**
     * 重复关联本项目：幂等 no-op，不更新
     */
    @Test
    void associate_sameProject_idempotentNoUpdate() {
        FileRecord record = new FileRecord();
        record.setId(FILE_ID);
        record.setProjectId(PROJECT_ID);
        when(fileRecordMapper.selectById(FILE_ID)).thenReturn(record);

        fileService.associateToProject(PROJECT_ID, FILE_ID);

        verify(fileRecordMapper, never()).updateById(any(FileRecord.class));
    }

    /**
     * 关联到其他项目（当前已归属他项目）：抛 6003，不更新
     */
    @Test
    void associate_boundToOtherProject_throws6003() {
        FileRecord record = new FileRecord();
        record.setId(FILE_ID);
        record.setProjectId(999L);
        when(fileRecordMapper.selectById(FILE_ID)).thenReturn(record);

        assertThatThrownBy(() -> fileService.associateToProject(PROJECT_ID, FILE_ID))
                .isInstanceOfSatisfying(BusinessException.class, e ->
                        assertThat(e.getCode()).isEqualTo(ResultCode.PROJECT_FILE_ALREADY_BOUND.getCode()));

        verify(fileRecordMapper, never()).updateById(any(FileRecord.class));
    }

    /**
     * 关联不存在文件：抛 2004
     */
    @Test
    void associate_fileNotFound_throws2004() {
        when(fileRecordMapper.selectById(FILE_ID)).thenReturn(null);

        assertThatThrownBy(() -> fileService.associateToProject(PROJECT_ID, FILE_ID))
                .isInstanceOfSatisfying(BusinessException.class, e ->
                        assertThat(e.getCode()).isEqualTo(ResultCode.FILE_NOT_FOUND.getCode()));
    }

    /**
     * 解除关联成功：归属该项目的文件 project_id 置空
     */
    @Test
    void dissociate_success_clearsOwnership() {
        FileRecord record = new FileRecord();
        record.setId(FILE_ID);
        record.setProjectId(PROJECT_ID);
        when(fileRecordMapper.selectById(FILE_ID)).thenReturn(record);

        fileService.dissociateFromProject(PROJECT_ID, FILE_ID);

        ArgumentCaptor<FileRecord> captor = ArgumentCaptor.forClass(FileRecord.class);
        verify(fileRecordMapper).updateById(captor.capture());
        assertThat(captor.getValue().getProjectId()).isNull();
    }

    /**
     * 解除未归属文件（null 或他项目）：抛 6004，不更新
     */
    @Test
    void dissociate_notBoundToProject_throws6004() {
        FileRecord unbound = new FileRecord();
        unbound.setId(FILE_ID);
        unbound.setProjectId(null);
        when(fileRecordMapper.selectById(FILE_ID)).thenReturn(unbound);

        assertThatThrownBy(() -> fileService.dissociateFromProject(PROJECT_ID, FILE_ID))
                .isInstanceOfSatisfying(BusinessException.class, e ->
                        assertThat(e.getCode()).isEqualTo(ResultCode.PROJECT_FILE_NOT_IN_PROJECT.getCode()));

        verify(fileRecordMapper, never()).updateById(any(FileRecord.class));
    }

    /**
     * 项目文件分页：IPage 记录转 PageResult，字段透传
     */
    @Test
    void pageByProject_success_convertsPageResult() {
        FileRecord record = new FileRecord();
        record.setId(FILE_ID);
        record.setProjectId(PROJECT_ID);
        record.setFileName("a.pdf");
        record.setStatus(FileStatus.UPLOADED);
        Page<FileRecord> page = new Page<>(1, 10, 1);
        page.setRecords(List.of(record));

        when(fileRecordMapper.selectPage(any(Page.class), any())).thenReturn(page);

        PageResult<?> result = fileService.pageByProject(PROJECT_ID, new PageQuery());

        assertThat(result.getTotal()).isEqualTo(1L);
        assertThat(result.getRecords()).hasSize(1);
    }

    // ==================== 项目文件ID非分页查询（Phase 6） ====================

    /**
     * listFileIdsByProject：映射记录主键为ID列表（升序由 SQL orderByAsc 保证）
     */
    @Test
    void listFileIdsByProject_mapsRecordIds() {
        FileRecord f1 = new FileRecord();
        f1.setId(1785800001L);
        FileRecord f2 = new FileRecord();
        f2.setId(1785800002L);
        when(fileRecordMapper.selectList(any(com.baomidou.mybatisplus.core.conditions.Wrapper.class)))
                .thenReturn(List.of(f1, f2));

        assertThat(fileService.listFileIdsByProject(PROJECT_ID))
                .containsExactly(1785800001L, 1785800002L);
    }

    /**
     * 项目无文件：返回空列表
     */
    @Test
    void listFileIdsByProject_emptyProject_returnsEmptyList() {
        when(fileRecordMapper.selectList(any(com.baomidou.mybatisplus.core.conditions.Wrapper.class)))
                .thenReturn(List.of());

        assertThat(fileService.listFileIdsByProject(PROJECT_ID)).isEmpty();
    }

    // ==================== 测试辅助 ====================

    private void stubRecord(FileStatus status) {
        FileRecord record = new FileRecord();
        record.setId(FILE_ID);
        record.setStatus(status);
        when(fileRecordMapper.selectById(FILE_ID)).thenReturn(record);
    }

    private void assertLegalWrite(FileStatus from, FileStatus to) {
        FileRecord record = new FileRecord();
        record.setId(FILE_ID);
        record.setStatus(from);
        when(fileRecordMapper.selectById(FILE_ID)).thenReturn(record);

        fileService.updateStatus(FILE_ID, to);

        ArgumentCaptor<FileRecord> captor = ArgumentCaptor.forClass(FileRecord.class);
        verify(fileRecordMapper).updateById(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo(to);
        org.mockito.Mockito.clearInvocations(fileRecordMapper);
    }

    private void assertThatIllegal(FileStatus from, FileStatus to) {
        FileRecord record = new FileRecord();
        record.setId(FILE_ID);
        record.setStatus(from);
        when(fileRecordMapper.selectById(FILE_ID)).thenReturn(record);

        assertThatThrownBy(() -> fileService.updateStatus(FILE_ID, to))
                .isInstanceOfSatisfying(BusinessException.class, e ->
                        assertThat(e.getCode()).isEqualTo(ResultCode.FILE_STATUS_ILLEGAL_TRANSITION.getCode()));
        verify(fileRecordMapper, never()).updateById(any(FileRecord.class));
        org.mockito.Mockito.clearInvocations(fileRecordMapper);
    }

    /**
     * 构造合法 PDF 上传文件（文件名扩展名决定 FileType 解析）
     */
    private MockMultipartFile pdfFile() {
        return new MockMultipartFile(
                "file", "test.pdf", MediaType.APPLICATION_PDF_VALUE, "fake-pdf".getBytes());
    }

    /**
     * 打桩存储落盘与 insert 回填雪花 ID
     */
    private void stubStorageAndInsert(String storedName) {
        when(fileStorageService.store(any(), any(FileType.class))).thenReturn("2026/09/" + storedName);
        when(fileRecordMapper.insert(any(FileRecord.class))).thenAnswer(inv -> {
            FileRecord r = inv.getArgument(0);
            r.setId(FILE_ID);
            return 1;
        });
    }
}