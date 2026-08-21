package com.aifp.aiagent.rag;

import java.util.List;

/**
 * 文本向量化服务
 * <p>
 * 薄封装 Spring AI {@code EmbeddingModel}（DashScope text-embedding-v2）。
 * 供后续阶段手动打分/检索使用；注意 {@code VectorStore.similaritySearch}
 * 内部会自行 embed 查询，无需重复调用。
 *
 * @author Tang_tzb
 */
public interface EmbeddingService {

    /**
     * 单文本向量化。
     *
     * @param text 文本
     * @return 向量
     */
    float[] embed(String text);

    /**
     * 批量文本向量化。
     *
     * @param texts 文本列表
     * @return 向量列表（顺序与输入一致）
     */
    List<float[]> embedBatch(List<String> texts);
}
