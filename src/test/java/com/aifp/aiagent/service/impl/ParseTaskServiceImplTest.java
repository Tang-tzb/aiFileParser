package com.aifp.aiagent.service.impl;

import com.aifp.aiagent.common.ResultCode;
import com.aifp.aiagent.dto.FileRecordVO;
import com.aifp.aiagent.dto.FormVO;
import com.aifp.aiagent.dto.TaskProgress;
import com.aifp.aiagent.dto.TaskStartVO;
import com.aifp.aiagent.entity.enums.FileStatus;
import com.aifp.aiagent.exception.BusinessException;
import com.aifp.aiagent.service.FileService;
import com.aifp.aiagent.service.FormService;
import com.aifp.aiagent.service.ProjectService;
import com.aifp.aiagent.task.AsyncParseExecutor;
import com.aifp.aiagent.task.ProgressPublisher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * {@link ParseTaskServiceImpl} 测试（离线）。
 * <p>
 * 验证 start()：校验 form/file/project、生成 taskId、发布初始 0%、触发异步执行、返回 VO；
 * form/file/project 不存在分别抛 5001/2004/6001；projectId 非空时校验文件归属（6004），
 * 不传 projectId 保持历史行为（旧 API 兼容）。
 *
 * @author Tang_tzb
 */
@ExtendWith(MockitoExtension.class)
class ParseTaskServiceImplTest {

    private static final Long FORM_ID = 1785508135L;
    private static final Long FILE_ID = 1785800001L;
    private static final Long PROJECT_ID = 1785900001L;

    @Mock
    private FormService formService;
    @Mock
    private FileService fileService;
    @Mock
    private ProjectService projectService;
    @Mock
    private ProgressPublisher progressPublisher;
    @Mock
    private AsyncParseExecutor asyncParseExecutor;

    private ParseTaskServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new ParseTaskServiceImpl(
                formService, fileService, projectService, progressPublisher, asyncParseExecutor);
    }

    @Test
    void start_valid_createsTaskPublishesInitialAndTriggersAsync() {
        when(formService.getFormById(FORM_ID)).thenReturn(new FormVO());
        when(fileService.getById(FILE_ID)).thenReturn(buildRecord(null));

        TaskStartVO vo = service.start(null, FORM_ID, FILE_ID);

        assertThat(vo.getTaskId()).isNotBlank();
        assertThat(vo.getFileId()).isEqualTo(FILE_ID);
        assertThat(vo.getFormId()).isEqualTo(FORM_ID);
        assertThat(vo.getCreateTime()).isNotNull();

        ArgumentCaptor<TaskProgress> captor = ArgumentCaptor.forClass(TaskProgress.class);
        verify(progressPublisher).publish(captor.capture());
        TaskProgress initial = captor.getValue();
        assertThat(initial.getStatus()).isEqualTo("PARSING");
        assertThat(initial.getPercent()).isZero();
        verify(asyncParseExecutor).run(eq(vo.getTaskId()), eq(FORM_ID), eq(FILE_ID));
        verifyNoInteractions(projectService);
    }

    /**
     * projectId 非空且文件归属该项目：校验通过正常创建任务
     */
    @Test
    void start_withProjectId_fileInProject_createsTask() {
        when(formService.getFormById(FORM_ID)).thenReturn(new FormVO());
        when(fileService.getById(FILE_ID)).thenReturn(buildRecord(PROJECT_ID));
        when(projectService.getProjectById(PROJECT_ID)).thenReturn(new com.aifp.aiagent.dto.ProjectVO());

        TaskStartVO vo = service.start(PROJECT_ID, FORM_ID, FILE_ID);

        assertThat(vo.getTaskId()).isNotBlank();
        verify(projectService).getProjectById(PROJECT_ID);
        verify(asyncParseExecutor).run(anyString(), eq(FORM_ID), eq(FILE_ID));
    }

    /**
     * projectId 非空但文件未归属该项目（归属为 null）：抛 6004，不发布不执行
     */
    @Test
    void start_withProjectId_fileNotInProject_throws6004() {
        when(formService.getFormById(FORM_ID)).thenReturn(new FormVO());
        when(fileService.getById(FILE_ID)).thenReturn(buildRecord(null));
        when(projectService.getProjectById(PROJECT_ID)).thenReturn(new com.aifp.aiagent.dto.ProjectVO());

        assertThatThrownBy(() -> service.start(PROJECT_ID, FORM_ID, FILE_ID))
                .isInstanceOfSatisfying(BusinessException.class, e ->
                        assertThat(e.getCode()).isEqualTo(ResultCode.PROJECT_FILE_NOT_IN_PROJECT.getCode()));

        verify(progressPublisher, never()).publish(any());
        verify(asyncParseExecutor, never()).run(anyString(), anyLong(), anyLong());
    }

    /**
     * projectId 对应项目不存在：抛 6001（ProjectService.getProjectById 内部校验）
     */
    @Test
    void start_withProjectId_projectNotFound_throws6001() {
        when(formService.getFormById(FORM_ID)).thenReturn(new FormVO());
        when(fileService.getById(FILE_ID)).thenReturn(buildRecord(PROJECT_ID));
        when(projectService.getProjectById(PROJECT_ID))
                .thenThrow(new BusinessException(ResultCode.PROJECT_NOT_FOUND));

        assertThatThrownBy(() -> service.start(PROJECT_ID, FORM_ID, FILE_ID))
                .isInstanceOfSatisfying(BusinessException.class, e ->
                        assertThat(e.getCode()).isEqualTo(ResultCode.PROJECT_NOT_FOUND.getCode()));

        verify(progressPublisher, never()).publish(any());
        verify(asyncParseExecutor, never()).run(anyString(), anyLong(), anyLong());
    }

    @Test
    void start_formNotFound_throws5001() {
        when(formService.getFormById(FORM_ID)).thenThrow(new BusinessException(ResultCode.FORM_NOT_FOUND));

        assertThatThrownBy(() -> service.start(null, FORM_ID, FILE_ID))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getCode()).isEqualTo(5001));
        verify(progressPublisher, never()).publish(any());
        verify(asyncParseExecutor, never()).run(anyString(), anyLong(), anyLong());
    }

    @Test
    void start_fileNotFound_throws2004() {
        when(formService.getFormById(FORM_ID)).thenReturn(new FormVO());
        when(fileService.getById(FILE_ID)).thenThrow(new BusinessException(ResultCode.FILE_NOT_FOUND));

        assertThatThrownBy(() -> service.start(null, FORM_ID, FILE_ID))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getCode()).isEqualTo(2004));
        verify(progressPublisher, never()).publish(any());
        verify(asyncParseExecutor, never()).run(anyString(), anyLong(), anyLong());
    }

    // ==================== 测试数据 ====================

    private FileRecordVO buildRecord(Long projectId) {
        FileRecordVO vo = new FileRecordVO();
        vo.setFileId(FILE_ID);
        vo.setProjectId(projectId);
        vo.setStatus(FileStatus.UPLOADED);
        return vo;
    }
}
