package com.aifp.aiagent.service;

/**
 * 项目访问权限服务（预留扩展点）
 * <p>
 * 架构约束：所有项目访问（详情/编辑/删除/文件/表单/结构化字段/RAG/跨项目比较）
 * 的权限判断必须经由本接口，由 Service 层调用，禁止散落到各 Controller。
 * <p>
 * 当前系统尚无用户体系，默认实现全放行；后续接入用户体系后，
 * 基于当前登录用户与项目的关系（个人/部门/权限项目）扩展实现，接口签名保持不变。
 *
 * @author Tang_tzb
 */
public interface ProjectAccessService {

    /**
     * 判断当前用户是否可访问指定项目
     *
     * @param projectId 项目ID
     * @return true 表示允许访问
     */
    boolean canAccess(Long projectId);
}
