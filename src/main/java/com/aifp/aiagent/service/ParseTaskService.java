package com.aifp.aiagent.service;

import com.aifp.aiagent.dto.TaskStartVO;

/**
 * 异步解析任务服务
 * <p>
 * 校验表单与文件后创建任务（taskId=UUID），发布初始 0% 进度并触发后台流水线，
 * 立即返回 taskId 供前端订阅 SSE 进度（{@code GET /task/progress/{taskId}}）。
 *
 * @author Tang_tzb
 */
public interface ParseTaskService {

    /**
     * 启动异步解析任务。
     *
     * @param projectId 项目ID（可选：传入时校验文件已归属该项目；null 走历史行为）
     * @param formId    表单 ID（决定 AI 抽取字段）
     * @param fileId    文件记录 ID（已上传）
     * @return 任务启动结果，含 taskId
     */
    TaskStartVO start(Long projectId, Long formId, Long fileId);
}
