package com.aifp.aiagent.service;

import com.aifp.aiagent.dto.ExtractionResult;
import com.aifp.aiagent.dto.FormFieldVO;

import java.util.List;

/**
 * 项目表单持久化服务（需求 §十 点名组件）
 * <p>
 * 职责：将 FieldExtractor 的成功抽取结果写入项目结构化事实表——
 * find-or-create 项目表单实例（project_form）、按来源文件同源替换写入字段值
 * （project_form_field_value，含定义快照 + 来源追溯列）、维护实例版本。
 * <p>
 * 边界约束：
 * <ul>
 *   <li>projectId 为 null 时完全跳过（历史数据兼容，不强制历史文件归属项目）</li>
 *   <li>替换边界 = projectFormId + sourceFileId，不触碰其他文件的字段值
 *      （多来源并存合法，冲突识别属 Phase 7 读取侧）</li>
 *   <li>version = 项目表单结构化数据<b>成功写入代数</b>，仅成功持久化递增</li>
 *   <li>无法确认的来源一律存 null，禁止按下标/相似度猜测</li>
 * </ul>
 *
 * @author Tang_tzb
 */
public interface ProjectFormPersistenceService {

    /**
     * 持久化一次抽取的结构化字段值。
     * <p>
     * 仅持久化校验通过的非空值；无可持久化值时整体跳过（不建实例、不升版本）。
     * 抽取失败（errors 非空但存在部分非空值）时仍持久化非空部分，错误随
     * {@link ExtractionResult#getErrors()} 返回给调用方。
     *
     * @param projectId 项目ID（null 时跳过持久化）
     * @param formId    表单定义ID
     * @param fileId    来源文件记录ID（替换边界的一部分）
     * @param fields    表单字段定义（快照来源）
     * @param result    抽取结果（values/rawValues/sources/sourcePages）
     */
    void persistExtraction(Long projectId, Long formId, Long fileId,
                           List<FormFieldVO> fields, ExtractionResult result);
}
