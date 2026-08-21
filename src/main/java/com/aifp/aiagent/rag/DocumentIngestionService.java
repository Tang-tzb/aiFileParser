package com.aifp.aiagent.rag;

/**
 * 文档入库编排服务
 * <p>
 * 串联 文件解析 → 切片 → 向量入库，补齐第五阶段独立 RAG 组件的编排缺口。
 * 供 FieldExtractorService 在检索前确保文档已落库 Milvus。
 *
 * @author Tang_tzb
 */
public interface DocumentIngestionService {

    /**
     * 按 fileId 入库：读取文件 → 解析 → 切片 → 向量化入 Milvus。
     * <p>
     * 幂等：若文件状态已为 SUCCESS 则跳过，避免产生重复 chunk。
     *
     * @param fileId 文件记录ID
     */
    void ingest(Long fileId);

    /**
     * 按 fileId 入库（带阶段回调）：在 PARSING/VECTORING 阶段起始时触发
     * {@code callback.onStageStart(stageCode)}，供上层（异步任务）发布 0%/50% 进度。
     * <p>
     * 幂等：若文件状态已为 SUCCESS 则跳过，且不触发回调（已成功任务无进度可报）。
     *
     * @param fileId   文件记录ID
     * @param callback 阶段回调，null 表示不回调（等价于 {@link #ingest(Long)}）
     */
    void ingest(Long fileId, ProgressCallback callback);
}
