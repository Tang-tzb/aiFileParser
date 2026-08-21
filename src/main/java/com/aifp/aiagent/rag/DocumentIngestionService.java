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
}
