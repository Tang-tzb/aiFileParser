package com.aifp.aiagent.rag;

import org.springframework.ai.document.Document;

import java.util.List;

/**
 * 向量存储服务
 * <p>
 * 薄封装 Spring AI {@code VectorStore}（Milvus）：负责切片的入库与相似检索。
 *
 * @author aiFileParser
 */
public interface VectorStoreService {

    /**
     * 存储切片（VectorStore 自动 embed + 入库）。
     *
     * @param chunks 切片列表
     */
    void store(List<Document> chunks);

    /**
     * 相似检索。
     *
     * @param query 查询文本
     * @param topK  返回条数
     * @return 命中的切片
     */
    List<Document> search(String query, int topK);
}
