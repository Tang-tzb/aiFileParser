package com.aifp.aiagent.parser.pdf.chunk;

import com.aifp.aiagent.parser.pdf.ast.DocumentAst;
import com.aifp.aiagent.parser.pdf.ast.DocumentNode;
import com.aifp.aiagent.parser.pdf.ast.DocumentNodeType;
import com.aifp.aiagent.parser.pdf.ast.TableNode;
import com.aifp.aiagent.parser.pdf.page.ElementSource;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link StructuralChunker} 结构切块测试（离线，真实 JTokkit 计数）。
 * <p>
 * 预算注入：结构层 chunkSize=60；降级层 chunkSize=40 / overlap=8。
 *
 * @author Tang_tzb
 */
class StructuralChunkerTest {

    private static StructuralChunker chunker;

    @BeforeAll
    static void setUp() {
        TokenCounter tokenCounter = new TokenCounter();
        tokenCounter.init();
        SemanticChunker semantic = new SemanticChunker(tokenCounter, new TableTextRenderer());
        ReflectionTestUtils.setField(semantic, "chunkSize", 40);
        ReflectionTestUtils.setField(semantic, "overlap", 8);
        chunker = new StructuralChunker(tokenCounter, semantic);
        ReflectionTestUtils.setField(chunker, "chunkSize", 60);
    }

    @Test
    void chunk_paragraphsAggregated_withinBudget() {
        DocumentAst ast = TestAstFactory.ast("样例.pdf", TestAstFactory.page(1, List.of(
                TestAstFactory.paragraph("第一段内容。"),
                TestAstFactory.paragraph("第二段内容。"),
                TestAstFactory.paragraph("第三段内容。")), null));

        List<ChunkSeed> seeds = chunker.chunk(ast);

        // 预算内聚合为单 PARAGRAPH 块，段落间空行连接
        assertThat(seeds).hasSize(1);
        assertThat(seeds.get(0).chunkType()).isEqualTo(ChunkType.PARAGRAPH);
        assertThat(seeds.get(0).content())
                .isEqualTo("第一段内容。\n\n第二段内容。\n\n第三段内容。");
    }

    @Test
    void chunk_paragraphOverflow_newChunkAtParagraphBoundary() {
        // 验收点 1：段落超预算只在段落边界开新块，段落完整不截断
        List<DocumentNode> nodes = new ArrayList<>();
        for (int i = 1; i <= 20; i++) {
            nodes.add(TestAstFactory.paragraph("第" + i + "段段落内容比较长一些用于测试预算边界行为。"));
        }
        DocumentAst ast = TestAstFactory.ast("样例.pdf", TestAstFactory.page(1, nodes, null));

        List<ChunkSeed> seeds = chunker.chunk(ast);

        assertThat(seeds.size()).isGreaterThan(1);
        assertThat(seeds).allSatisfy(s -> assertThat(s.chunkType()).isEqualTo(ChunkType.PARAGRAPH));
        String all = seeds.stream().map(ChunkSeed::content).collect(Collectors.joining("\n\n"));
        for (int i = 1; i <= 20; i++) {
            assertThat(all).contains("第" + i + "段段落内容比较长一些用于测试预算边界行为。");
        }
    }

    @Test
    void chunk_titlePath_propagatesToBodyAndStackPops() {
        // 验收点 2：标题与正文保持关系；fontSize 降序排名决定层级（D3）
        DocumentAst ast = TestAstFactory.ast("样例.pdf", TestAstFactory.page(1, List.of(
                TestAstFactory.title("第一章", 20f),
                TestAstFactory.paragraph("正文甲。"),
                TestAstFactory.title("第二节", 15f),
                TestAstFactory.paragraph("正文乙。"),
                TestAstFactory.title("另章", 20f),
                TestAstFactory.paragraph("正文丙。")), null));

        List<ChunkSeed> seeds = chunker.chunk(ast);

        // 每个标题冲刷缓冲 → 3 个正文块
        List<ChunkSeed> paragraphs = seeds.stream()
                .filter(s -> s.chunkType() == ChunkType.PARAGRAPH).toList();
        assertThat(paragraphs).hasSize(3);
        assertThat(paragraphs.get(0).titlePath()).isEqualTo("第一章");
        assertThat(paragraphs.get(1).titlePath()).isEqualTo("第一章 / 第二节");
        assertThat(paragraphs.get(2).titlePath()).isEqualTo("另章");
    }

    @Test
    void chunk_smallTable_fullBlockAndKvExcluded() {
        DocumentAst ast = TestAstFactory.ast("样例.pdf", TestAstFactory.page(1,
                List.of(TestAstFactory.table(3, 2)),
                List.of(TestAstFactory.kv("建设单位", "甲单位"))));

        List<ChunkSeed> seeds = chunker.chunk(ast);

        // 验收点 4：小表整块成 TABLE 块
        assertThat(seeds).hasSize(1);
        assertThat(seeds.get(0).chunkType()).isEqualTo(ChunkType.TABLE);
        assertThat(seeds.get(0).content()).contains("| 表头0 | 表头1 |").contains("| 数据2_0 | 数据2_1 |");
        // 硬约束 B：整块表格页 KV 互斥跳过（表格优先）
        assertThat(seeds).noneSatisfy(s -> assertThat(s.chunkType()).isEqualTo(ChunkType.KEY_VALUE));
    }

    @Test
    void chunk_largeTable_rowGroupsAndKvCompensation() {
        DocumentAst ast = TestAstFactory.ast("样例.pdf", TestAstFactory.page(1,
                List.of(TestAstFactory.table(12, 4)),
                List.of(TestAstFactory.kv("建设单位", "甲单位"), TestAstFactory.kv("设计单位", "乙设计院"))));

        List<ChunkSeed> seeds = chunker.chunk(ast);

        // 大表 → 行组块；KV 补偿块整体出现（验收点 3/5）
        List<ChunkSeed> tables = seeds.stream()
                .filter(s -> s.chunkType() == ChunkType.TABLE).toList();
        List<ChunkSeed> kvs = seeds.stream()
                .filter(s -> s.chunkType() == ChunkType.KEY_VALUE).toList();
        assertThat(tables.size()).isGreaterThan(1);
        assertThat(kvs).hasSize(1);
        assertThat(kvs.get(0).content())
                .contains("建设单位：甲单位").contains("设计单位：乙设计院");
    }

    @Test
    void chunk_pageWithoutTable_kvStandalone() {
        // 防御分支（D1）：无表格页的独立 KV 来源整组成块
        DocumentAst ast = TestAstFactory.ast("样例.pdf", TestAstFactory.page(1,
                List.of(TestAstFactory.paragraph("正文内容。")),
                List.of(TestAstFactory.kv("键名称", "值内容"))));

        List<ChunkSeed> seeds = chunker.chunk(ast);

        // 页末 KV 先输出；段落缓冲在文档末尾冲刷殿后
        assertThat(seeds).extracting(ChunkSeed::chunkType)
                .containsExactly(ChunkType.KEY_VALUE, ChunkType.PARAGRAPH);
        assertThat(seeds.get(0).content()).isEqualTo("键名称：值内容");
    }

    @Test
    void chunk_headerFooterAndPlaceholders_skipped() {
        // 决策 D2：页眉页脚与占位节点不进 chunk
        DocumentNode header = new DocumentNode(DocumentNodeType.HEADER,
                ElementSource.PDF_TEXT, 1.0f, null, "页眉重复文本");
        DocumentNode footer = new DocumentNode(DocumentNodeType.FOOTER,
                ElementSource.PDF_TEXT, 1.0f, null, "页脚重复文本");
        DocumentNode image = new DocumentNode(DocumentNodeType.IMAGE,
                ElementSource.IMAGE, null, null, "[IMAGE: 第1页第1张]");
        DocumentAst ast = TestAstFactory.ast("样例.pdf", TestAstFactory.page(1, List.of(
                header, TestAstFactory.paragraph("正文内容。"), footer, image), null));

        List<ChunkSeed> seeds = chunker.chunk(ast);

        assertThat(seeds).hasSize(1);
        assertThat(seeds.get(0).content()).isEqualTo("正文内容。");
    }

    @Test
    void chunk_crossPageSection_pageRangeRecorded() {
        // 页边界不冲刷：章节跨页连续，pageStart/pageEnd 记录范围
        DocumentAst ast = TestAstFactory.ast("样例.pdf",
                TestAstFactory.page(1, List.of(TestAstFactory.paragraph("第一页正文内容。")), null),
                TestAstFactory.page(2, List.of(TestAstFactory.paragraph("第二页正文内容。")), null));

        List<ChunkSeed> seeds = chunker.chunk(ast);

        assertThat(seeds).hasSize(1);
        assertThat(seeds.get(0).pageStart()).isEqualTo(1);
        assertThat(seeds.get(0).pageEnd()).isEqualTo(2);
        assertThat(seeds.get(0).content())
                .contains("第一页正文内容。").contains("第二页正文内容。");
    }

    @Test
    void chunk_oversizedParagraph_degradedToSentence() {
        // 段落自身超预算 → SemanticChunker 句子降级，元数据继承段落
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < 8; i++) {
            text.append("这是超长段落的第").append(i).append("句，内容足够长以触发降级切分逻辑判定。");
        }
        DocumentAst ast = TestAstFactory.ast("样例.pdf", TestAstFactory.page(1, List.of(
                TestAstFactory.paragraph(text.toString())), null));

        List<ChunkSeed> seeds = chunker.chunk(ast);

        assertThat(seeds.size()).isGreaterThan(1);
        assertThat(seeds).allSatisfy(s -> {
            assertThat(s.chunkType()).isEqualTo(ChunkType.SENTENCE);
            assertThat(s.pageStart()).isEqualTo(1);
            assertThat(s.pageEnd()).isEqualTo(1);
            assertThat(s.titlePath()).isEmpty();
        });
    }

    @Test
    void chunk_tableFlushesParagraphBuffer_beforeTableBlock() {
        // 表格前冲刷段落缓冲：段落块与表格块顺序分离
        DocumentAst ast = TestAstFactory.ast("样例.pdf", TestAstFactory.page(1, List.of(
                TestAstFactory.paragraph("表格前的段落。"),
                TestAstFactory.table(3, 2)), null));

        List<ChunkSeed> seeds = chunker.chunk(ast);

        assertThat(seeds).hasSize(2);
        assertThat(seeds.get(0).chunkType()).isEqualTo(ChunkType.PARAGRAPH);
        assertThat(seeds.get(0).content()).isEqualTo("表格前的段落。");
        assertThat(seeds.get(1).chunkType()).isEqualTo(ChunkType.TABLE);
    }

    @Test
    void chunk_nullAst_returnsEmpty() {
        assertThat(chunker.chunk(null)).isEmpty();
    }

    @Test
    void chunk_emptyTable_skipped() {
        TableNode emptyTable = new TableNode(0, 0, List.of(), ElementSource.PDF_TEXT, null);
        DocumentAst ast = TestAstFactory.ast("样例.pdf", TestAstFactory.page(1,
                List.of(emptyTable), List.of(TestAstFactory.kv("键名称", "值内容"))));

        List<ChunkSeed> seeds = chunker.chunk(ast);

        // 空表跳过（无表格形态记录）→ KV 防御性成块
        assertThat(seeds).extracting(ChunkSeed::chunkType)
                .containsExactly(ChunkType.KEY_VALUE);
    }
}
