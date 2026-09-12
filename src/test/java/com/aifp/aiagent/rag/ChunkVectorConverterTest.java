package com.aifp.aiagent.rag;

import com.aifp.aiagent.parser.pdf.chunk.Chunk;
import com.aifp.aiagent.parser.pdf.chunk.ChunkType;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link ChunkVectorConverter} 测试：metadata 映射、null 键省略、空值跳过。
 *
 * @author Tang_tzb
 */
class ChunkVectorConverterTest {

    private final ChunkVectorConverter converter = new ChunkVectorConverter();

    @Test
    void convert_metadataMapped() {
        Chunk chunk = Chunk.builder()
                .content("块内容")
                .fileId("1785800001")
                .fileName("样例.pdf")
                .pageStart(1)
                .pageEnd(2)
                .titlePath("第一章 / 第二节")
                .chunkType(ChunkType.TABLE)
                .chunkIndex(0)
                .totalChunks(3)
                .bbox("50.0,600.0,400.0,120.0")
                .confidence(0.6f)
                .sourceType("PDF_TEXT,OCR")
                .build();

        List<Document> documents = converter.convert(List.of(chunk));

        assertThat(documents).hasSize(1);
        assertThat(documents.get(0).getText()).isEqualTo("块内容");
        Map<String, Object> meta = documents.get(0).getMetadata();
        assertThat(meta)
                .containsEntry("fileId", "1785800001")
                .containsEntry("fileName", "样例.pdf")
                .containsEntry("pageStart", 1)
                .containsEntry("pageEnd", 2)
                .containsEntry("titlePath", "第一章 / 第二节")
                .containsEntry("chunkType", "TABLE")
                .containsEntry("chunkIndex", 0)
                .containsEntry("totalChunks", 3)
                .containsEntry("bbox", "50.0,600.0,400.0,120.0")
                .containsEntry("confidence", 0.6f)
                .containsEntry("sourceType", "PDF_TEXT,OCR");
    }

    @Test
    void convert_optionalNull_omitted() {
        Chunk chunk = Chunk.builder()
                .content("x")
                .chunkType(ChunkType.PARAGRAPH)
                .chunkIndex(0)
                .totalChunks(1)
                .build();

        Map<String, Object> meta = converter.convert(List.of(chunk)).get(0).getMetadata();

        // null 值一律省略键（Spring AI metadata 拒绝 null；Milvus 标量空值规避）
        assertThat(meta).doesNotContainKeys("bbox", "confidence", "sourceType",
                "fileId", "fileName", "pageStart", "pageEnd", "titlePath");
        assertThat(meta)
                .containsEntry("chunkType", "PARAGRAPH")
                .containsEntry("chunkIndex", 0)
                .containsEntry("totalChunks", 1);
    }

    @Test
    void convert_nullOrBlankContent_skipped() {
        assertThat(converter.convert(null)).isEmpty();
        // Arrays.asList 允许 null 元素（List.of 不允许）；null 块与空白内容块均跳过
        assertThat(converter.convert(java.util.Arrays.asList(
                null, Chunk.builder().content("  ").build()))).isEmpty();
    }

    /**
     * Phase 5：2-arg 重载注入文件级 projectId metadata（String，与 fileId 约定一致）。
     */
    @Test
    void convert_withProjectId_metadataContainsProjectId() {
        Chunk chunk = Chunk.builder()
                .content("块内容")
                .fileId("1785800001")
                .chunkType(ChunkType.PARAGRAPH)
                .chunkIndex(0)
                .totalChunks(1)
                .build();

        List<Document> documents = converter.convert(List.of(chunk), 1785900001L);

        assertThat(documents.get(0).getMetadata())
                .containsEntry("projectId", "1785900001")
                .containsEntry("fileId", "1785800001");
    }

    /**
     * Phase 5：projectId 为 null（历史文件）时省略键，产物与历史 chunk 一致（§三十一）。
     */
    @Test
    void convert_nullProjectId_keyOmitted() {
        Chunk chunk = Chunk.builder()
                .content("块内容")
                .fileId("1785800001")
                .chunkType(ChunkType.PARAGRAPH)
                .chunkIndex(0)
                .totalChunks(1)
                .build();

        assertThat(converter.convert(List.of(chunk), null).get(0).getMetadata())
                .doesNotContainKey("projectId");
    }
}
