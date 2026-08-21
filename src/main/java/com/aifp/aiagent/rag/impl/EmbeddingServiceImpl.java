package com.aifp.aiagent.rag.impl;

import com.aifp.aiagent.common.ResultCode;
import com.aifp.aiagent.exception.BusinessException;
import com.aifp.aiagent.rag.EmbeddingService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingResponse;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * EmbeddingService 实现：基于 Spring AI {@link EmbeddingModel}（DashScope）。
 * <p>
 * Spring AI 1.0.0 中 {@code embed(String)} 返回 {@code float[]}。
 *
 * @author Tang_tzb
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EmbeddingServiceImpl implements EmbeddingService {

    private final EmbeddingModel embeddingModel;

    @Override
    public float[] embed(String text) {
        try {
            return embeddingModel.embed(text);
        } catch (Exception e) {
            log.error("向量化失败: {}", e.getMessage(), e);
            throw new BusinessException(ResultCode.AI_INVOKE_ERROR, "文本向量化失败");
        }
    }

    @Override
    public List<float[]> embedBatch(List<String> texts) {
        try {
            EmbeddingResponse response = embeddingModel.embedForResponse(texts);
            List<float[]> result = new ArrayList<>(response.getResults().size());
            response.getResults().forEach(embedding -> result.add(embedding.getOutput()));
            return result;
        } catch (Exception e) {
            log.error("批量向量化失败: {}", e.getMessage(), e);
            throw new BusinessException(ResultCode.AI_INVOKE_ERROR, "批量文本向量化失败");
        }
    }
}
