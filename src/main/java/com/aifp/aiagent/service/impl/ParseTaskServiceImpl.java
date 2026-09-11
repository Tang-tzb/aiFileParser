package com.aifp.aiagent.service.impl;

import com.aifp.aiagent.common.ResultCode;
import com.aifp.aiagent.dto.FileRecordVO;
import com.aifp.aiagent.dto.TaskProgress;
import com.aifp.aiagent.dto.TaskStartVO;
import com.aifp.aiagent.exception.BusinessException;
import com.aifp.aiagent.service.FileService;
import com.aifp.aiagent.service.FormService;
import com.aifp.aiagent.service.ParseTaskService;
import com.aifp.aiagent.service.ProjectService;
import com.aifp.aiagent.task.AsyncParseExecutor;
import com.aifp.aiagent.task.ProgressPublisher;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * 异步解析任务服务实现
 * <p>
 * 编排：校验 form/file → 生成 taskId → 发布初始 0% → 触发异步执行 → 返回 taskId。
 * 校验复用 FormService/FileService/ProjectService，表单/文件/项目不存在抛 5001/2004/6001。
 * projectId 兼容策略：传入时强校验文件归属；不传保持历史行为（旧 API 兼容）。
 *
 * @author Tang_tzb
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ParseTaskServiceImpl implements ParseTaskService {

    private final FormService formService;
    private final FileService fileService;
    private final ProjectService projectService;
    private final ProgressPublisher progressPublisher;
    private final AsyncParseExecutor asyncParseExecutor;

    @Override
    public TaskStartVO start(Long projectId, Long formId, Long fileId) {
        // 校验存在：getFormById 抛 5001，getById 抛 2004；getProjectById 内部校验权限+存在（6001/403）
        formService.getFormById(formId);
        FileRecordVO file = fileService.getById(fileId);
        if (projectId != null) {
            ensureFileInProject(projectId, file);
        }

        String taskId = UUID.randomUUID().toString();
        publishInitial(taskId, formId, fileId);
        // projectId 穿透（需求 §十）：异步链路保持项目归属，抽取成功后持久化到项目域
        asyncParseExecutor.run(taskId, projectId, formId, fileId);
        log.info("异步任务已创建 taskId={}, projectId={}, formId={}, fileId={}",
                taskId, projectId, formId, fileId);
        return new TaskStartVO(taskId, fileId, formId, LocalDateTime.now());
    }

    /**
     * 项目归属校验：文件当前 project_id 必须与请求 projectId 一致（null 视为未归属）。
     */
    private void ensureFileInProject(Long projectId, FileRecordVO file) {
        projectService.getProjectById(projectId);
        if (!projectId.equals(file.getProjectId())) {
            throw new BusinessException(ResultCode.PROJECT_FILE_NOT_IN_PROJECT,
                    "文件未关联该项目 fileId=" + file.getFileId() + ", projectId=" + projectId);
        }
    }

    /**
     * 发布任务创建首帧，前端立即收到 0% 反馈，避免异步线程启动前的空窗。
     */
    private void publishInitial(String taskId, Long formId, Long fileId) {
        TaskProgress initial = new TaskProgress(taskId, fileId, formId,
                "PARSING", 0, "任务已创建", null);
        progressPublisher.publish(initial);
    }
}
