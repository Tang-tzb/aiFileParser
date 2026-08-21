package com.aifp.aiagent.rag;

import org.springframework.ai.document.Document;

import java.util.List;

/**
 * 向量存储服务
 * <p>
 * 薄封装 Spring AI {@code VectorStore}（Milvus）：负责切片的入库与相似检索。
 *
 * @author Tang_tzb
 */
public interface VectorStoreService {

    /**
     * 存储切片（VectorStore 自动 embed + 入库）。
     *
     * @param chunks 切片列表
     */
    void store(List<Document> chunks);

    /**
     * 相似检索（不带过滤表达式）。
     *
     * @param query 查询文本
     * @param topK  返回条数
     * @return 命中的切片
     */
    List<Document> search(String query, int topK);

    /**
     * 相似检索（带元数据过滤表达式）。
     * <p>
     * 例如按 fileId 过滤：{@code filterExpression = "fileId == '1785800001'"}，
     * 避免多文件场景下的跨文件污染。{@code filterExpression} 为 null 时不过滤。
     *
     * @param query            查询文本
     * @param topK             返回条数
     * @param filterExpression Milvus 标量过滤表达式，可空
     * @return 命中的切片
     */
    List<Document> search(String query, int topK, String filterExpression);
}
