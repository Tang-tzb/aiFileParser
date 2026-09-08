package com.aifp.aiagent.parser.pdf.chunk;

import com.aifp.aiagent.parser.pdf.ast.TableNode;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link SemanticChunker} 降级切片测试（离线，真实 JTokkit 计数）。
 * <p>
 * 预算注入：chunkSize=40 / overlap=8（小预算便于触发降级）。
 *
 * @author Tang_tzb
 */
class SemanticChunkerTest {

    private static SemanticChunker chunker;

    private static TokenCounter tokenCounter;

    @BeforeAll
    static void setUp() {
        tokenCounter = new TokenCounter();
        tokenCounter.init();
        chunker = new SemanticChunker(tokenCounter, new TableTextRenderer());
        ReflectionTestUtils.setField(chunker, "chunkSize", 40);
        ReflectionTestUtils.setField(chunker, "overlap", 8);
    }

    @Test
    void splitParagraph_sentenceBoundary_noMidSentenceCut() {
        String paragraph = "第一句话内容完整无缺。第二句话同样完整无缺。第三句话依然完整无缺。"
                + "第四句话保持完整无缺。第五句话还是完整无缺。第六句话始终完整无缺。";

        List<ChunkSeed> seeds = chunker.splitParagraph(paragraph);

        // 多句超预算 → 多 SENTENCE 块（验收点 1：句子完整不截断）
        assertThat(seeds.size()).isGreaterThan(1);
        assertThat(seeds).allSatisfy(s -> assertThat(s.chunkType()).isEqualTo(ChunkType.SENTENCE));
        // 句子层无重叠：无损拼接 == 原文
        String joined = seeds.stream().map(ChunkSeed::content).collect(Collectors.joining());
        assertThat(joined).isEqualTo(paragraph);
        // 每块以句末标点收尾（句子边界）
        assertThat(seeds).allSatisfy(s -> assertThat(s.content()).endsWith("。"));
    }

    @Test
    void splitParagraph_singleOversizedSentence_tokenWindowWithOverlap() {
        // 动态构造无句末标点的单句直至 token 超预算（重复字符会被 BPE 合并，不可静态假设）
        StringBuilder sentenceBuilder = new StringBuilder();
        int part = 0;
        while (tokenCounter.count(sentenceBuilder.toString()) <= 45) {
            sentenceBuilder.append("超长句段").append(part++);
        }
        String sentence = sentenceBuilder.toString();

        List<ChunkSeed> seeds = chunker.splitParagraph(sentence);

        // 单句超预算 → TOKEN 窗口切分（D6：overlap 生效层）
        assertThat(seeds.size()).isGreaterThan(1);
        assertThat(seeds).allSatisfy(s -> assertThat(s.chunkType()).isEqualTo(ChunkType.TOKEN));
        // 相邻窗口重叠：块 i+1 开头文本出现在块 i 尾部（token 级滑窗语义）
        for (int i = 1; i < seeds.size(); i++) {
            String prev = seeds.get(i - 1).content();
            String cur = seeds.get(i).content();
            String head = cur.substring(0, Math.min(4, cur.length()));
            assertThat(prev).contains(head);
        }
    }

    @Test
    void splitParagraph_blank_returnsEmpty() {
        assertThat(chunker.splitParagraph(null)).isEmpty();
        assertThat(chunker.splitParagraph("   ")).isEmpty();
    }

    @Test
    void splitTable_smallTable_singleChunkComplete() {
        TableNode table = TestAstFactory.table(3, 2);

        List<ChunkSeed> seeds = chunker.splitTable(table);

        // 小表整块（验收点 4：不按行拆分）
        assertThat(seeds).hasSize(1);
        assertThat(seeds.get(0).chunkType()).isEqualTo(ChunkType.TABLE);
        String content = seeds.get(0).content();
        assertThat(content).contains("| 表头0 | 表头1 |");
        assertThat(content).contains("| --- | --- |");
        assertThat(content).contains("| 数据1_0 | 数据1_1 |");
        assertThat(content).contains("| 数据2_0 | 数据2_1 |");
    }

    @Test
    void splitTable_largeTable_rowGroupsRepeatHeader() {
        TableNode table = TestAstFactory.table(12, 4);

        List<ChunkSeed> seeds = chunker.splitTable(table);

        // 大表 → 多行组块（验收点 5：每组重复表头）
        assertThat(seeds.size()).isGreaterThan(1);
        String headerRow = "| 表头0 | 表头1 | 表头2 | 表头3 |";
        String delimiter = "| --- | --- | --- | --- |";
        assertThat(seeds).allSatisfy(s -> {
            assertThat(s.chunkType()).isEqualTo(ChunkType.TABLE);
            assertThat(s.content()).startsWith(headerRow + "\n" + delimiter + "\n");
        });
        // 行组并集覆盖全部数据行（不丢行、行原子不拆）
        String allRows = seeds.stream()
                .map(s -> s.content().substring(s.content().indexOf(delimiter) + delimiter.length()))
                .collect(Collectors.joining("\n"));
        for (int r = 1; r < 12; r++) {
            assertThat(allRows).contains("| 数据" + r + "_0 | 数据" + r + "_1 | 数据" + r + "_2 | 数据" + r + "_3 |");
        }
    }

    @Test
    void splitTable_nullOrEmpty_returnsEmpty() {
        assertThat(chunker.splitTable(null)).isEmpty();
        assertThat(chunker.splitTable(new TableNode(0, 0, List.of(), null, null))).isEmpty();
    }

    @Test
    void splitKeyValues_pairsAtomic_groupedAtPairBoundary() {
        List<ChunkSeed> seeds = chunker.splitKeyValues(List.of(
                TestAstFactory.kv("建设单位", "亳州市教育局"),
                TestAstFactory.kv("工程名称", "职教园一期工程"),
                TestAstFactory.kv("建设地址", "养生大道以南古井大道以东"),
                TestAstFactory.kv("合同工期", "三百六十五个日历天")));

        // 多对聚合成块（预算内单块；分组只发生在对边界，验收点 3）
        assertThat(seeds).isNotEmpty();
        String joined = seeds.stream().map(ChunkSeed::content).collect(Collectors.joining("\n"));
        assertThat(joined).isEqualTo("建设单位：亳州市教育局\n工程名称：职教园一期工程"
                + "\n建设地址：养生大道以南古井大道以东\n合同工期：三百六十五个日历天");
        // 每块行均为完整 key：value 对
        assertThat(seeds).allSatisfy(s -> {
            for (String line : s.content().split("\n")) {
                assertThat(line).contains("：");
            }
        });
    }

    @Test
    void splitKeyValues_blankKeySkippedAndValueNewlineNormalized() {
        List<ChunkSeed> seeds = chunker.splitKeyValues(List.of(
                TestAstFactory.kv("  ", "无效键"),
                TestAstFactory.kv("建设规模", "南地块位于大道以南\n北地块位于经开区")));

        assertThat(seeds).hasSize(1);
        assertThat(seeds.get(0).content()).isEqualTo("建设规模：南地块位于大道以南 北地块位于经开区");
    }

    @Test
    void splitKeyValues_nullOrEmpty_returnsEmpty() {
        assertThat(chunker.splitKeyValues(null)).isEmpty();
        assertThat(chunker.splitKeyValues(List.of(TestAstFactory.kv("  ", "v")))).isEmpty();
    }
}
