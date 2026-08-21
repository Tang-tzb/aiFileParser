package com.aifp.aiagent.service.impl;

import com.aifp.aiagent.dto.TaskProgress;
import com.aifp.aiagent.dto.TaskStartVO;
import com.aifp.aiagent.service.FileService;
import com.aifp.aiagent.service.FormService;
import com.aifp.aiagent.service.ParseTaskService;
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
 * 校验复用 FormService/FileService，表单/文件不存在抛 5001/2004。
 *
 * @author Tang_tzb
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ParseTaskServiceImpl implements ParseTaskService {

    private final FormService formService;
    private final FileService fileService;
    private final ProgressPublisher progressPublisher;
    private final AsyncParseExecutor asyncParseExecutor;

    @Override
    public TaskStartVO start(Long formId, Long fileId) {
        // 校验存在：getFormById 抛 5001，getById 抛 2004
        formService.getFormById(formId);
        fileService.getById(fileId);

        String taskId = UUID.randomUUID().toString();
        publishInitial(taskId, formId, fileId);
        asyncParseExecutor.run(taskId, formId, fileId);
        log.info("异步任务已创建 taskId={}, formId={}, fileId={}", taskId, formId, fileId);
        return new TaskStartVO(taskId, fileId, formId, LocalDateTime.now());
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
