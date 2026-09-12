package com.aifp.aiagent.service;

import com.aifp.aiagent.dto.ProjectStructuredFactsVO;

/**
 * 项目结构化事实查询服务（需求 §二十一 Structured Query，Phase 7）
 * <p>
 * 职责边界（约束 7）：只提供<b>结构化事实读取</b>能力——
 * 禁止调用 ChatModel、拼装 Prompt 或生成自然语言回答（全部属 Phase 8 Project Assistant）。
 * <p>
 * 数据访问边界（约束 1）：项目级全量事实查询集中在 Service 内完成，
 * Controller / Assistant 不得直接访问 Mapper；后续增加 fieldCode 精查时
 * 仅需在本接口扩展查询维度，实现内部已按 (projectFormId, fieldCode) 分组组织数据。
 * <p>
 * 事实边界（约束 8）：以 {@code projectFormId + fieldCode} 为最小事实单元，
 * 不同 ProjectForm 的同 fieldCode 不合并不裁决。
 *
 * @author Tang_tzb
 */
public interface ProjectQueryService {

    /**
     * 查询项目级结构化事实（当前阶段为全量查询）。
     * <p>
     * 仅返回 ACTIVE ProjectForm 实例与其未逻辑删除的字段值（约束 2）；
     * 冲突判定由 {@link FieldValueConflictResolver} 基于 normalizedValue 完成，
     * conflict=true 时完整保留全部来源值（约束 3），不做任何取值裁决（约束 5）。
     * 来源文件缺失仅置空 sourceFileName，不影响整体查询（约束 9）。
     *
     * @param projectId 项目ID（非空；权限/存在性经 ProjectService 守门，403/6001）
     * @return 项目结构化事实聚合（无表单实例/字段值时返回空结构，非错误）
     */
    ProjectStructuredFactsVO queryProjectFacts(Long projectId);
}
