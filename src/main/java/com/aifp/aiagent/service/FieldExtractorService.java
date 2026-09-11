package com.aifp.aiagent.service;

import com.aifp.aiagent.dto.ExtractionResult;

/**
 * AI 自动填表核心服务
 * <p>
 * 输入 formId + fileId，根据表单字段定义动态生成查询，从 Milvus 检索相关切片，
 * 调用 Qwen-Plus 抽取全字段，经 JSON Schema 校验 + 智能类型转换 + Retry 反馈后返回
 * {@link ExtractionResult}。字段变化无需修改代码。
 *
 * @author Tang_tzb
 */
public interface FieldExtractorService {

    /**
     * 根据 formId 的字段定义，从 fileId 对应文档中抽取字段值（历史 API，保留兼容）。
     * <p>
     * 含可靠性保障：Schema 校验失败时按 {@code rag.extract.retry.max-attempts} 重试，
     * 将字段级错误反馈给 AI 修正，最终返回类型化值、剩余错误与 LLM 调用次数。
     * <p>
     * projectId 语义（需求 §九）：内部根据 fileId 查询其归属项目；
     * 文件未归属任何项目时跳过项目域持久化，抽取行为不变。
     *
     * @param formId 表单ID
     * @param fileId 文件记录ID
     * @return 抽取可靠性结果（类型化值 + 字段错误 + 调用次数）
     */
    ExtractionResult extract(Long formId, Long fileId);

    /**
     * 项目维度抽取（需求 §十）：抽取成功且 projectId 非空时，
     * 先经 {@link ProjectFormPersistenceService} 持久化结构化字段值（溯源/版本），
     * 再写文件 SUCCESS 状态；持久化失败走失败链路，不返回"抽取成功"。
     *
     * @param projectId 项目ID（null 等价旧方法语义，跳过持久化）
     * @param formId    表单ID
     * @param fileId    文件记录ID
     * @return 抽取可靠性结果（类型化值 + 原始值 + 来源 + 字段错误 + 调用次数）
     */
    ExtractionResult extract(Long projectId, Long formId, Long fileId);
}
