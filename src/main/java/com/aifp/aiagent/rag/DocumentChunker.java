package com.aifp.aiagent.rag;

import com.aifp.aiagent.document.ParserDocument;
import com.aifp.aiagent.document.ParserDocumentMetadata;
import com.knuddels.jtokkit.Encodings;
import com.knuddels.jtokkit.api.Encoding;
import com.knuddels.jtokkit.api.EncodingRegistry;
import com.knuddels.jtokkit.api.EncodingType;
import com.knuddels.jtokkit.api.IntArrayList;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 文档切片器
 * <p>
 * 基于 token 的滑动窗口切片：用 JTokkit (cl100k_base) 将全文编码为 token，
 * 按 {@code chunkSize} 切块、相邻块重叠 {@code overlap} token 以保留上下文。
 * 输出 Spring AI {@link org.springframework.ai.document.Document}，可被
 * {@code VectorStore} 原生接收。
 * <p>
 * 注意：cl100k_base 为 GPT 系分词，对 Qwen 等模型为近似计数（Spring AI 的
 * TokenTextSplitter 同样使用 cl100k_base）。
 *
 * @author aiFileParser
 */
@Slf4j
@Component
public class DocumentChunker {

    private static final EncodingType ENCODING_TYPE = EncodingType.CL100K_BASE;

    @Value("${rag.chunk.size:800}")
    private int chunkSize;

    @Value("${rag.chunk.overlap:200}")
    private int overlap;

    private Encoding encoding;

    @PostConstruct
    void init() {
        EncodingRegistry registry = Encodings.newDefaultEncodingRegistry();
        this.encoding = registry.getEncoding(ENCODING_TYPE);
        log.info("DocumentChunker 初始化 chunkSize={}, overlap={}", chunkSize, overlap);
    }

    /**
     * 将解析文档切片为若干 Spring AI Document。
     *
     * @param doc 解析文档（含全文与元数据）
     * @return 切片列表；内容为空时返回空列表
     */
    public List<org.springframework.ai.document.Document> chunk(ParserDocument doc) {
        String content = doc.getContent();
        if (content == null || content.isBlank()) {
            return List.of();
        }

        IntArrayList tokens = encoding.encode(content);
        int total = tokens.size();

        // 单块即可容纳
        if (total <= chunkSize) {
            return List.of(buildDocument(encoding.decode(tokens), doc.getMetadata(), 0, 1));
        }

        int stride = Math.max(1, chunkSize - overlap);
        // 滑动窗口实际切片数：首个窗口覆盖 [0, chunkSize)，其后每步前进 stride，
        // 最后一个窗口满足 start + chunkSize >= total 即结束。
        // 推导：最小 k 使 k*stride + chunkSize >= total，k = ceil((total - chunkSize) / stride)，
        // 切片数 = k + 1。用整数向上取整避免浮点误差。
        int numChunks = ((total - chunkSize + stride - 1) / stride) + 1;
        List<org.springframework.ai.document.Document> chunks = new ArrayList<>(numChunks);

        int index = 0;
        int start = 0;
        while (start < total) {
            int end = Math.min(start + chunkSize, total);
            IntArrayList sub = new IntArrayList(end - start);
            for (int i = start; i < end; i++) {
                sub.add(tokens.get(i));
            }
            chunks.add(buildDocument(encoding.decode(sub), doc.getMetadata(), index, numChunks));
            index++;
            if (end >= total) {
                break;
            }
            start += stride;
        }
        log.info("切片完成 fileName={}, tokens={}, chunks={}",
                doc.getMetadata().getFileName(), total, chunks.size());
        return chunks;
    }

    /**
     * 构造单个 Spring AI Document
     */
    private org.springframework.ai.document.Document buildDocument(
            String text, ParserDocumentMetadata meta, int chunkIndex, int totalChunks) {
        // 元数据值类型保持与原字段语义一致：文本类用 String，数值类用 Integer，
        // 便于 Milvus 元数据过滤及后续 AI Prompt 生成时保留数值语义。
        Map<String, Object> chunkMeta = Map.of(
                "fileName", nullSafe(meta.getFileName()),
                "fileType", nullSafe(meta.getType()),
                "page", meta.getPage(),
                "chunkIndex", chunkIndex,
                "totalChunks", totalChunks
        );
        return new org.springframework.ai.document.Document(text, chunkMeta);
    }

    private String nullSafe(Object o) {
        return o == null ? "" : o.toString();
    }
}
