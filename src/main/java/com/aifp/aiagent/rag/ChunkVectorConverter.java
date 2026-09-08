package com.aifp.aiagent.rag;

import com.aifp.aiagent.parser.pdf.chunk.Chunk;
import org.springframework.ai.document.Document;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Chunk → Spring AI Document 转换器（阶段 12）：将混合语义切片产物映射为
 * {@code VectorStore} 原生可接收的 Document。
 * <p>
 * metadata 映射约定（与旧 DocumentChunker 值类型约定一致，数值用 Integer
 * 便于 Milvus 标量过滤与 AI Prompt 数值语义）：fileId(String)/fileName/
 * pageStart/pageEnd/titlePath/chunkType(code)/chunkIndex/totalChunks/sourceType；
 * 可选字段 bbox/confidence 为 null 时<b>省略键</b>（避免 Milvus 标量空值问题）。
 *
 * @author Tang_tzb
 */
@Component
public class ChunkVectorConverter {

    /**
     * 批量转换：null 输入 → 空列表；空内容块跳过（不入向量库）。
     *
     * @param chunks 切片列表（可 null）
     * @return Spring AI Document 列表
     */
    public List<Document> convert(List<Chunk> chunks) {
        if (chunks == null) {
            return List.of();
        }
        List<Document> documents = new ArrayList<>(chunks.size());
        for (Chunk chunk : chunks) {
            if (chunk != null && chunk.getContent() != null && !chunk.getContent().isBlank()) {
                documents.add(new Document(chunk.getContent(), buildMetadata(chunk)));
            }
        }
        return documents;
    }

    /**
     * 单块 metadata 构建：null 值一律省略键（Spring AI Document metadata
     * 拒绝 null 值，且 Milvus 标量空值需规避）；字段存在但缺失时同样省略。
     */
    private Map<String, Object> buildMetadata(Chunk chunk) {
        Map<String, Object> metadata = new HashMap<>();
        putIfNotNull(metadata, "fileId", chunk.getFileId());
        putIfNotNull(metadata, "fileName", chunk.getFileName());
        putIfNotNull(metadata, "pageStart", chunk.getPageStart());
        putIfNotNull(metadata, "pageEnd", chunk.getPageEnd());
        putIfNotNull(metadata, "titlePath", chunk.getTitlePath());
        putIfNotNull(metadata, "chunkType",
                chunk.getChunkType() == null ? null : chunk.getChunkType().getCode());
        putIfNotNull(metadata, "chunkIndex", chunk.getChunkIndex());
        putIfNotNull(metadata, "totalChunks", chunk.getTotalChunks());
        putIfNotNull(metadata, "sourceType", chunk.getSourceType());
        putIfNotNull(metadata, "bbox", chunk.getBbox());
        putIfNotNull(metadata, "confidence", chunk.getConfidence());
        return metadata;
    }

    /**
     * null 值省略键。
     */
    private void putIfNotNull(Map<String, Object> metadata, String key, Object value) {
        if (value != null) {
            metadata.put(key, value);
        }
    }
}
