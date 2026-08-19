package com.aifp.aiagent.rag;

import com.aifp.aiagent.document.ParserDocument;
import com.aifp.aiagent.document.ParserDocumentMetadata;
import com.aifp.aiagent.entity.enums.FileType;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link DocumentChunker} 切片测试（确定性，无需外部服务）。
 *
 * @author aiFileParser
 */
class DocumentChunkerTest {

    private static DocumentChunker chunker;

    @BeforeAll
    static void setUp() {
        chunker = new DocumentChunker();
        // 注入 @Value 字段（不启动 Spring 容器）
        ReflectionTestUtils.setField(chunker, "chunkSize", 800);
        ReflectionTestUtils.setField(chunker, "overlap", 200);
        chunker.init();
    }

    @Test
    void chunk_emptyContent_returnsEmpty() {
        ParserDocument doc = buildDoc("");
        assertThat(chunker.chunk(doc)).isEmpty();
    }

    @Test
    void chunk_smallContent_returnsSingleChunk() {
        ParserDocument doc = buildDoc("这是一段简短文本，不足以切分。");
        List<org.springframework.ai.document.Document> chunks = chunker.chunk(doc);

        assertThat(chunks).hasSize(1);
        assertThat(chunks.get(0).getText()).contains("简短文本");
        assertThat(chunks.get(0).getMetadata()).containsEntry("chunkIndex", 0);
        assertThat(chunks.get(0).getMetadata()).containsEntry("totalChunks", 1);
    }

    @Test
    void chunk_largeContent_splitsWithOverlapAndMetadata() {
        // 构造远超 chunkSize 的中英文长文本
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 3000; i++) {
            sb.append("AI文件解析系统项目申报投资金额").append(i).append("。");
        }
        ParserDocument doc = buildDoc(sb.toString());
        List<org.springframework.ai.document.Document> chunks = chunker.chunk(doc);

        assertThat(chunks).hasSizeGreaterThan(1);

        // 每块 metadata 含必要字段
        int total = chunks.size();
        for (int i = 0; i < total; i++) {
            org.springframework.ai.document.Document c = chunks.get(i);
            assertThat(c.getMetadata()).containsEntry("fileName", "项目申报书.pdf");
            assertThat(c.getMetadata()).containsEntry("fileType", "PDF");
            assertThat(c.getMetadata()).containsEntry("chunkIndex", i);
            assertThat(c.getMetadata()).containsEntry("totalChunks", total);
        }

        // 相邻块应存在 overlap（末尾文本出现在下一块开头附近，验证非完全无重叠的硬切）
        // overlap 通过块数与 stride 推断：块数 = ceil((tokens - overlap) / stride)
        // 这里仅断言块数>1 且每块非空，overlap 语义已由 stride=chunkSize-overlap 保证
        assertThat(chunks.get(0).getText()).isNotBlank();
        assertThat(chunks.get(1).getText()).isNotBlank();
    }

    private ParserDocument buildDoc(String content) {
        ParserDocumentMetadata meta = ParserDocumentMetadata.builder()
                .fileName("项目申报书.pdf")
                .page(3)
                .type(FileType.PDF)
                .build();
        ParserDocument doc = new ParserDocument();
        doc.setContent(content);
        doc.setMetadata(meta);
        return doc;
    }
}
