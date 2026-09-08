package com.aifp.aiagent.parser.pdf.chunk;

import com.aifp.aiagent.parser.pdf.ast.KeyValueNode;
import com.aifp.aiagent.parser.pdf.ast.TableNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 语义降级切片器（阶段 12）：对超长结构块执行优先级链最后两级降级——
 * Sentence（句子边界）→ Token Length（token 窗口，overlap 仅此层生效，
 * 见阶段 12 计划决策 D6）。无状态算法组件，由 {@link StructuralChunker} 调用。
 * <p>
 * 降级规则：
 * <ul>
 *   <li>超长段落：句末标点/换行切句 → 贪心聚合到 token 预算（SENTENCE 块）；
 *       单句仍超预算 → token 窗口切（TOKEN 块），块间句子完整不截断；</li>
 *   <li>大表格：首行为表头行，贪心添加完整行（行 = 原子，单行超预算也独立成组
 *       不拆行内，Table 优先级 &gt; Sentence），每组渲染 GFM 并<b>重复表头</b>；</li>
 *   <li>键值组：贪心聚合完整 {@code key：value} 对（\n 连接），单对超预算
 *       独立成块，<b>绝不拆散单对</b>（阶段验收点 3）。</li>
 * </ul>
 *
 * @author Tang_tzb
 */
@Slf4j
@Component
public class SemanticChunker {

    private final TokenCounter tokenCounter;

    private final TableTextRenderer tableTextRenderer;

    /**
     * 单块 token 预算（cl100k_base 近似计数）
     */
    @Value("${document.parser.pdf.chunk.size:800}")
    private int chunkSize;

    /**
     * token 窗口降级层的块间重叠 token（仅 TOKEN 层生效）
     */
    @Value("${document.parser.pdf.chunk.overlap:200}")
    private int overlap;

    public SemanticChunker(TokenCounter tokenCounter, TableTextRenderer tableTextRenderer) {
        this.tokenCounter = tokenCounter;
        this.tableTextRenderer = tableTextRenderer;
    }

    /**
     * 超长段落降级：句子边界切分 + 贪心聚合；单句超预算走 token 窗口。
     * 前置条件：段落 token &gt; chunkSize（由 StructuralChunker 判定后调用）；
     * 不满足时防御性返回单 SENTENCE 块。
     *
     * @param text 超长段落文本（可 null）
     * @return SENTENCE/TOKEN 块种子列表（仅 content/chunkType，待合并元数据）
     */
    public List<ChunkSeed> splitParagraph(String text) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        List<ChunkSeed> result = new ArrayList<>();
        List<String> buf = new ArrayList<>();
        int bufTokens = 0;
        for (String sentence : splitSentences(text)) {
            int sentenceTokens = tokenCounter.count(sentence);
            if (sentenceTokens > chunkSize) {
                // 单句超预算：先冲刷句子缓冲，再 token 窗口切分（TOKEN 块）
                flushSentenceBuffer(result, buf);
                for (String part : tokenCounter.window(sentence, chunkSize, overlap)) {
                    result.add(ChunkSeed.of(part, ChunkType.TOKEN));
                }
                bufTokens = 0;
            } else if (bufTokens + sentenceTokens > chunkSize && !buf.isEmpty()) {
                // 加入该句将超预算：冲刷当前句组，该句开新组
                flushSentenceBuffer(result, buf);
                buf.add(sentence);
                bufTokens = sentenceTokens;
            } else {
                buf.add(sentence);
                bufTokens += sentenceTokens;
            }
        }
        flushSentenceBuffer(result, buf);
        return result;
    }

    /**
     * 表格降级：token ≤ 预算整表单块；大表格按"表头 + N 行"行组贪心拆分，
     * 每组重复表头（阶段验收点 5）。
     *
     * @param table 表格节点（可 null）
     * @return TABLE 块种子列表（仅 content/chunkType）
     */
    public List<ChunkSeed> splitTable(TableNode table) {
        String fullTable = tableTextRenderer.render(table);
        if (fullTable == null) {
            return List.of();
        }
        if (tokenCounter.count(fullTable) <= chunkSize) {
            return List.of(ChunkSeed.of(fullTable, ChunkType.TABLE));
        }
        return splitLargeTableRows(table);
    }

    /**
     * 键值组降级：贪心聚合完整 {@code key：value} 对；单对超预算独立成块，
     * 绝不拆散单对（阶段验收点 3）。key 空白的对跳过。
     *
     * @param keyValues 键值节点列表（可 null）
     * @return KEY_VALUE 块种子列表（仅 content/chunkType）
     */
    public List<ChunkSeed> splitKeyValues(List<KeyValueNode> keyValues) {
        List<String> lines = new ArrayList<>();
        if (keyValues != null) {
            for (KeyValueNode kv : keyValues) {
                if (kv == null || kv.getKey() == null || kv.getKey().isBlank()) {
                    continue;
                }
                lines.add(formatKv(kv));
            }
        }
        if (lines.isEmpty()) {
            return List.of();
        }
        List<ChunkSeed> result = new ArrayList<>();
        List<String> buf = new ArrayList<>();
        int bufTokens = 0;
        for (String line : lines) {
            int lineTokens = tokenCounter.count(line);
            if (bufTokens + lineTokens > chunkSize && !buf.isEmpty()) {
                // 单对为原子：仅在对边界切组；当前组非空才冲刷
                result.add(ChunkSeed.of(String.join("\n", buf), ChunkType.KEY_VALUE));
                buf.clear();
                bufTokens = 0;
            }
            buf.add(line);
            bufTokens += lineTokens;
        }
        if (!buf.isEmpty()) {
            result.add(ChunkSeed.of(String.join("\n", buf), ChunkType.KEY_VALUE));
        }
        return result;
    }

    /**
     * 大表格行组贪心拆分：表头模板（表头行 + 分隔行）token 为组固定开销，
     * 逐行累加，超预算且当前组已有数据行时切组；单行超预算不切（行原子，
     * 组内不拆行内），允许该组超出预算（Table 优先级 &gt; Sentence）。
     */
    private List<ChunkSeed> splitLargeTableRows(TableNode table) {
        String[][] grid = tableTextRenderer.buildGrid(table);
        if (grid == null) {
            return List.of();
        }
        int headerTemplateTokens = tokenCounter.count(tableTextRenderer.renderRow(grid[0])
                + "\n" + tableTextRenderer.renderDelimiter(table.getColumnCount()));
        List<ChunkSeed> groups = new ArrayList<>();
        int groupStart = 1;
        int accTokens = headerTemplateTokens;
        for (int r = 1; r < table.getRowCount(); r++) {
            int rowTokens = tokenCounter.count(tableTextRenderer.renderRow(grid[r]));
            if (accTokens + rowTokens > chunkSize && r > groupStart) {
                groups.add(ChunkSeed.of(
                        tableTextRenderer.renderRowGroup(table, groupStart, r), ChunkType.TABLE));
                groupStart = r;
                accTokens = headerTemplateTokens;
            }
            accTokens += rowTokens;
        }
        if (groupStart < table.getRowCount()) {
            groups.add(ChunkSeed.of(
                    tableTextRenderer.renderRowGroup(table, groupStart, table.getRowCount()),
                    ChunkType.TABLE));
        }
        return groups;
    }

    /**
     * 句子切分：句末标点（。！？；!?;）与换行处断句，标点保留在句尾；
     * 空白句丢弃。
     */
    private List<String> splitSentences(String text) {
        List<String> sentences = new ArrayList<>();
        StringBuilder buf = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            buf.append(c);
            if (isSentenceEnd(c)) {
                sentences.add(buf.toString());
                buf.setLength(0);
            }
        }
        if (buf.length() > 0) {
            sentences.add(buf.toString());
        }
        return sentences.stream().filter(s -> !s.isBlank()).toList();
    }

    /**
     * 句末判定：中英文句末标点 + 换行。
     */
    private boolean isSentenceEnd(char c) {
        return c == '。' || c == '！' || c == '？' || c == '；'
                || c == '!' || c == '?' || c == ';' || c == '\n';
    }

    /**
     * 冲刷句子缓冲为 SENTENCE 块（句子间直接连接还原原文流，句子自带标点）。
     */
    private void flushSentenceBuffer(List<ChunkSeed> out, List<String> buf) {
        if (!buf.isEmpty()) {
            out.add(ChunkSeed.of(String.join("", buf), ChunkType.SENTENCE));
            buf.clear();
        }
    }

    /**
     * 键值对格式化：{@code key：value}（value 换行归一为空格，
     * 与阶段 11 MarkdownRenderer KV 渲染口径一致）。
     */
    private String formatKv(KeyValueNode kv) {
        String value = kv.getValue() == null ? "" : kv.getValue();
        return kv.getKey() + "：" + value.replace("\r\n", "\n").replace('\n', ' ');
    }
}
