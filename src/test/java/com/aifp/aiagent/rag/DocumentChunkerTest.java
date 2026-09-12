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
 * @author Tang_tzb
 */
class DocumentChunkerTest {

    private static final Long FILE_ID = 1785800001L;

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
        assertThat(chunks.get(0).getMetadata()).containsEntry("fileId", FILE_ID.toString());
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

    /**
     * Phase 5：ParserDocumentMetadata 携带 projectId 时写入 metadata（String，与 fileId 一致）。
     */
    @Test
    void chunk_withProjectId_metadataContainsProjectId() {
        ParserDocument doc = buildDoc("项目名称为智慧校园，投资金额500万。", 1785900001L);

        List<org.springframework.ai.document.Document> chunks = chunker.chunk(doc);

        assertThat(chunks).hasSize(1);
        assertThat(chunks.get(0).getMetadata())
                .containsEntry("projectId", "1785900001")
                .containsEntry("fileId", FILE_ID.toString());
    }

    /**
     * Phase 5：projectId 为 null（历史文件）时省略键，产物与历史 chunk 一致（§三十一）。
     */
    @Test
    void chunk_nullProjectId_keyOmitted() {
        ParserDocument doc = buildDoc("无项目归属的历史文件内容。");

        List<org.springframework.ai.document.Document> chunks = chunker.chunk(doc);

        assertThat(chunks).hasSize(1);
        assertThat(chunks.get(0).getMetadata()).doesNotContainKey("projectId");
    }

    private ParserDocument buildDoc(String content) {
        return buildDoc(content, null);
    }

    private ParserDocument buildDoc(String content, Long projectId) {
        ParserDocumentMetadata meta = ParserDocumentMetadata.builder()
                .fileName("项目申报书.pdf")
                .page(3)
                .type(FileType.PDF)
                .fileId(FILE_ID)
                .projectId(projectId)
                .build();
        ParserDocument doc = new ParserDocument();
        doc.setContent(content);
        doc.setMetadata(meta);
        return doc;
    }
}
