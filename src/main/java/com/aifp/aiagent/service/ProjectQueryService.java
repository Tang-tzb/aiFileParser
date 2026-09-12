package com.aifp.aiagent.service;

import com.aifp.aiagent.dto.ProjectFieldDictionaryVO;
import com.aifp.aiagent.dto.ProjectFieldFactVO;
import com.aifp.aiagent.dto.ProjectStructuredFactsVO;

import java.util.List;

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

    /**
     * 跨项目字段字典（Phase 10 Comparison / Ranking / Aggregation）。
     * <p>
     * 可访问项目集合内 fieldCode 快照去重投影（form id 升序第一为准），
     * 供 ComparisonSlotExtractor 做字段定位；后端以字典成员资格校验槽位
     * （追加约束 9/12）。仅 ACTIVE 实例；无实例/值时返回空列表，非错误。
     *
     * @param projectIds 项目ID集合（非空、不含 null，允许乱序自动去重；
     *                   逐项目经 ProjectService 守门 403/6001）
     * @return 字段字典项（fieldCode 升序）
     */
    List<ProjectFieldDictionaryVO> queryFieldDictionary(List<Long> projectIds);

    /**
     * 跨项目字段事实查询单字段（Phase 10；事实单元 = (projectId, projectFormId, fieldCode)，
     * 追加约束 4：不同表单实例独立输出，同项目多实例分别成单元）。
     * <p>
     * 仅 ACTIVE 实例；实例无该字段值时输出空 values 单元（不省略，供
     * "项目X 未提供该字段数据"解释与追加约束 12 无据判定）；冲突判定复用
     * {@link FieldValueConflictResolver}，conflict=true 完整保留全部来源值（追加约束 5）。
     *
     * @param projectIds 项目ID集合（非空、不含 null；逐项目经 ProjectService 守门 403/6001）
     * @param fieldCode  字段编码（非空白）
     * @return 字段事实单元列表（form id 升序）
     */
    List<ProjectFieldFactVO> queryFieldFacts(List<Long> projectIds, String fieldCode);
}
