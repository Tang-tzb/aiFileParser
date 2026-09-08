package com.aifp.aiagent.service.impl;

import com.aifp.aiagent.common.ResultCode;
import com.aifp.aiagent.entity.FileRecord;
import com.aifp.aiagent.entity.enums.FileStatus;
import com.aifp.aiagent.exception.BusinessException;
import com.aifp.aiagent.repository.FileRecordMapper;
import com.aifp.aiagent.service.storage.FileStorageService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * {@link FileServiceImpl#updateStatus} 状态机唯一写入口测试（阶段 13）
 * <p>
 * 验证：合法迁移正常落库；同态写幂等 no-op 不落库；非法迁移
 * （含用户点名的 SUCCESS → EXTRACTING / SUCCESS → PARSING / VECTORING → PARSING）
 * 抛 {@code FILE_STATUS_ILLEGAL_TRANSITION} 且不落库。
 *
 * @author Tang_tzb
 */
@ExtendWith(MockitoExtension.class)
class FileServiceImplTest {

    private static final Long FILE_ID = 1785800001L;

    @Mock
    private FileRecordMapper fileRecordMapper;
    @Mock
    private FileStorageService fileStorageService;

    private FileServiceImpl fileService;

    @BeforeEach
    void setUp() {
        fileService = new FileServiceImpl(fileRecordMapper, fileStorageService);
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
}