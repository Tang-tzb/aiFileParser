package com.aifp.aiagent.parser.pdf.chunk;

import com.aifp.aiagent.parser.pdf.ast.DocumentAst;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link HybridSemanticChunker} 门面测试（离线）：全局编号、metadata 完整性、
 * 可选字段聚合规则、import 纯度防线。
 *
 * @author Tang_tzb
 */
class HybridSemanticChunkerTest {

    private static HybridSemanticChunker chunker;

    @BeforeAll
    static void setUp() {
        TokenCounter tokenCounter = new TokenCounter();
        tokenCounter.init();
        SemanticChunker semantic = new SemanticChunker(tokenCounter, new TableTextRenderer());
        ReflectionTestUtils.setField(semantic, "chunkSize", 40);
        ReflectionTestUtils.setField(semantic, "overlap", 8);
        StructuralChunker structural = new StructuralChunker(tokenCounter, semantic);
        ReflectionTestUtils.setField(structural, "chunkSize", 60);
        chunker = new HybridSemanticChunker(structural);
    }

    @Test
    void chunk_globalNumberingAndCompleteMetadata() {
        DocumentAst ast = TestAstFactory.ast("样例.pdf", TestAstFactory.page(1, List.of(
                TestAstFactory.title("第一章", 20f),
                TestAstFactory.paragraph("正文内容甲。"),
                TestAstFactory.paragraph("正文内容乙。")), null));

        List<Chunk> chunks = chunker.chunk(ast, 1785800001L);

        assertThat(chunks).isNotEmpty();
        for (int i = 0; i < chunks.size(); i++) {
            Chunk c = chunks.get(i);
            // 验收点 6：完整 metadata（9 必需字段）
            assertThat(c.getChunkIndex()).isEqualTo(i);
            assertThat(c.getTotalChunks()).isEqualTo(chunks.size());
            assertThat(c.getFileId()).isEqualTo("1785800001");
            assertThat(c.getFileName()).isEqualTo("样例.pdf");
            assertThat(c.getPageStart()).isEqualTo(1);
            assertThat(c.getPageEnd()).isEqualTo(1);
            assertThat(c.getTitlePath()).isNotNull();
            assertThat(c.getChunkType()).isNotNull();
            assertThat(c.getContent()).isNotBlank();
        }
        // 验收点 2：标题与正文保持关系
        assertThat(chunks.get(0).getTitlePath()).isEqualTo("第一章");
    }

    @Test
    void chunk_nullAstOrNoContent_returnsEmpty() {
        assertThat(chunker.chunk(null, 1L)).isEmpty();
        assertThat(chunker.chunk(TestAstFactory.ast("空.pdf"), 1L)).isEmpty();
    }

    @Test
    void chunk_bboxUnion_confidenceMin_sourceTypeDedup() {
        // 原生段(PDF_TEXT,1.0,bbox y=700) + OCR 段(OCR,0.6,bbox y=600) 聚合为一块
        DocumentAst ast = TestAstFactory.ast("样例.pdf", TestAstFactory.page(1, List.of(
                TestAstFactory.paragraph("原生文字段落甲。"),
                TestAstFactory.ocrParagraph("识别文字段落乙。")), null));

        List<Chunk> chunks = chunker.chunk(ast, 1L);

        assertThat(chunks).hasSize(1);
        assertThat(chunks.get(0).getBbox()).isEqualTo("50.0,600.0,400.0,120.0");
        assertThat(chunks.get(0).getConfidence()).isEqualTo(0.6f);
        assertThat(chunks.get(0).getSourceType()).isEqualTo("PDF_TEXT,OCR");
    }

    @Test
    void chunk_nullFileId_serializedToEmptyString() {
        DocumentAst ast = TestAstFactory.ast("样例.pdf", TestAstFactory.page(1,
                List.of(TestAstFactory.paragraph("正文内容。")), null));

        List<Chunk> chunks = chunker.chunk(ast, null);

        assertThat(chunks).hasSize(1);
        assertThat(chunks.get(0).getFileId()).isEmpty();
    }

    /**
     * 依赖纯度防线（阶段 12 契约"只消费 DocumentAst"）：chunk 包主类仅允许
     * JDK / Spring / ast 模型 / BoundingBox / ElementSource / JTokkit / lombok，
     * 禁止 PageDocument、PDFBox、OCR 等解析底层类型渗入。
     */
    @Test
    void chunkPackageImports_pureAstConsumption_guard() throws Exception {
        List<String> sources = List.of("ChunkType.java", "Chunk.java", "ChunkSeed.java",
                "TokenCounter.java", "TableTextRenderer.java", "SemanticChunker.java",
                "StructuralChunker.java", "HybridSemanticChunker.java");
        for (String name : sources) {
            Path source = Path.of("src/main/java/com/aifp/aiagent/parser/pdf/chunk/" + name);
            org.junit.jupiter.api.Assumptions.assumeTrue(Files.exists(source), name + " 不存在");

            List<String> imports = Files.readAllLines(source).stream()
                    .filter(line -> line.startsWith("import "))
                    .map(line -> line.substring("import ".length(), line.indexOf(';')))
                    .toList();
            assertThat(imports).as("%s 的 import", name).allSatisfy(pkg ->
                    assertThat(pkg).satisfiesAnyOf(
                            p -> assertThat(p).startsWith("java."),
                            p -> assertThat(p).startsWith("jakarta."),
                            p -> assertThat(p).startsWith("org.springframework."),
                            p -> assertThat(p).startsWith("com.aifp.aiagent.parser.pdf.ast."),
                            p -> assertThat(p).startsWith("com.aifp.aiagent.parser.pdf.text."),
                            p -> assertThat(p).isEqualTo("com.aifp.aiagent.parser.pdf.page.ElementSource"),
                            p -> assertThat(p).startsWith("com.knuddels.jtokkit."),
                            p -> assertThat(p).startsWith("lombok.")));
        }
    }
}
