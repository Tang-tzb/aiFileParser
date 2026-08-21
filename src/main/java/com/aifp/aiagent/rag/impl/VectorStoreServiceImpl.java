package com.aifp.aiagent.rag.impl;

import com.aifp.aiagent.common.ResultCode;
import com.aifp.aiagent.exception.BusinessException;
import com.aifp.aiagent.rag.VectorStoreService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * VectorStoreService 实现：基于 Spring AI {@link VectorStore}（Milvus）。
 *
 * @author Tang_tzb
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class VectorStoreServiceImpl implements VectorStoreService {

    private final VectorStore vectorStore;

    @Override
    public void store(List<Document> chunks) {
        try {
            vectorStore.add(chunks);
            log.info("向量入库完成 chunks={}", chunks.size());
        } catch (Exception e) {
            log.error("向量入库失败: {}", e.getMessage(), e);
            throw new BusinessException(ResultCode.VECTOR_STORE_ERROR, "向量入库失败");
        }
    }

    @Override
    public List<Document> search(String query, int topK) {
        return search(query, topK, null);
    }

    @Override
    public List<Document> search(String query, int topK, String filterExpression) {
        try {
            SearchRequest.Builder builder = SearchRequest.builder().query(query).topK(topK);
            if (filterExpression != null && !filterExpression.isBlank()) {
                builder.filterExpression(filterExpression);
            }
            List<Document> results = vectorStore.similaritySearch(builder.build());
            log.info("向量检索完成 query={}, filter={}, hits={}", query, filterExpression, results.size());
            return results;
        } catch (Exception e) {
            log.error("向量检索失败: {}", e.getMessage(), e);
            throw new BusinessException(ResultCode.VECTOR_RETRIEVE_ERROR, "向量检索失败");
        }
    }
}
