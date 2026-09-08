package com.aifp.aiagent.rag;

/**
 * 文档入库编排服务
 * <p>
 * 串联 文件解析 → 清洗 → 切片 → 向量化入库（阶段 13 全链：
 * ingest → parse → clean → chunk → embedding → milvus）。
 * 供 FieldExtractorService 在检索前确保文档已落库 Milvus。
 * <p>
 * 阶段 13 状态机契约：ingest 完成停在 {@code VECTORING}（不落 SUCCESS），
 * SUCCESS 由抽取阶段落库，全链严格序列
 * UPLOADED → PARSING → VECTORING → EXTRACTING → SUCCESS / FAILED。
 *
 * @author Tang_tzb
 */
public interface DocumentIngestionService {

    /**
     * 按 fileId 入库：读取文件 → 解析 → 切片 → 向量化入 Milvus。
     * <p>
     * 幂等（阶段 13）：文件已向量化入库即跳过（SUCCESS / VECTORING / EXTRACTING），
     * 避免重复 chunk；FAILED / UPLOADED / PARSING 状态重新全量入库。
     *
     * @param fileId 文件记录ID
     */
    void ingest(Long fileId);

    /**
     * 按 fileId 入库（带阶段回调）：在 PARSING/VECTORING 阶段起始时触发
     * {@code callback.onStageStart(stageCode)}，供上层（异步任务）发布 0%/50% 进度。
     * <p>
     * 幂等（阶段 13）：文件已向量化入库（SUCCESS / VECTORING / EXTRACTING）即跳过，
     * 且不触发回调（已入库任务无入库进度可报）。
     *
     * @param fileId   文件记录ID
     * @param callback 阶段回调，null 表示不回调（等价于 {@link #ingest(Long)}）
     */
    void ingest(Long fileId, ProgressCallback callback);
}
