package com.aifp.aiagent.parser.pdf.chunk;

import com.aifp.aiagent.parser.pdf.ast.*;
import com.aifp.aiagent.parser.pdf.page.ElementSource;
import com.aifp.aiagent.parser.pdf.text.BoundingBox;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * 结构切块器（阶段 12）：按切片优先级链（标题 &gt; 章节 &gt; 段落 &gt; Table
 * &gt; KeyValue）将 {@link DocumentAst} 流式切块，超长内容降级交
 * {@link SemanticChunker}（Sentence → Token Length）。只消费 DocumentAst，
 * 禁止依赖 PageDocument/PDFBox/OCR 等解析底层类型（阶段 12 契约）。
 * <p>
 * 切块状态机（文档级流式，页序 × nodes 阅读序）：
 * <ul>
 *   <li><b>标题（优先级 1）</b>：TITLE 冲刷段落缓冲并更新 titlePath 栈——层级 =
 *       文档级 fontSize 降序去重排名（确定性启发式，决策 D3），弹出层级 ≥
 *       当前的祖先再压入；</li>
 *   <li><b>段落（优先级 3）</b>：贪心聚合到 token 预算成 PARAGRAPH 块（段落
 *       原子不截断）；段落自身超预算 → SemanticChunker 句子/token 降级；</li>
 *   <li><b>表格（优先级 4）</b>：冲刷段落缓冲后整表或行组成 TABLE 块；</li>
 *   <li><b>键值（优先级 5，页级互斥，决策 D1）</b>：页内存在整块表格 → 该页
 *       FUSION KVs 跳过（表格优先，硬约束 B）；页内存在大表拆分 → 该页 KVs
 *       整体成 KEY_VALUE 块补偿；无表格页的独立 KV 来源防御性成块；</li>
 *   <li><b>跳过（决策 D2）</b>：HEADER/FOOTER（非正文）与 IMAGE/STAMP/SIGNATURE
 *       （占位不承载正文）；LIST 归入段落聚合处理（预留位，决策 D9）。</li>
 * </ul>
 * 页边界不冲刷缓冲（章节跨页连续），pageStart/pageEnd 记录跨页范围。
 *
 * @author Tang_tzb
 */
@Slf4j
@Component
public class StructuralChunker {

    private final TokenCounter tokenCounter;

    private final SemanticChunker semanticChunker;

    /**
     * 单块 token 预算（cl100k_base 近似计数）
     */
    @Value("${document.parser.pdf.chunk.size:800}")
    private int chunkSize;

    public StructuralChunker(TokenCounter tokenCounter, SemanticChunker semanticChunker) {
        this.tokenCounter = tokenCounter;
        this.semanticChunker = semanticChunker;
    }

    /**
     * bbox 聚合：并集外接矩形 → "x,y,width,height"；全空返回 null。
     */
    private static String unionBbox(List<BoundingBox> bboxes) {
        BoundingBox union = null;
        for (BoundingBox bbox : bboxes) {
            union = union == null ? bbox : union.union(bbox);
        }
        return formatBbox(union);
    }

    /**
     * bbox 格式化："x,y,width,height"；null 返回 null。
     */
    private static String formatBbox(BoundingBox bbox) {
        return bbox == null ? null
                : bbox.getX() + "," + bbox.getY() + "," + bbox.getWidth() + "," + bbox.getHeight();
    }

    /**
     * 置信度聚合：非空最小值（保守）；全空返回 null。
     */
    private static Float minConfidence(List<Float> confidences) {
        return confidences.stream().filter(Objects::nonNull).min(Float::compare).orElse(null);
    }

    /**
     * 来源串：ElementSource.name()；null 返回 null。
     */
    private static String sourceName(ElementSource source) {
        return source == null ? null : source.name();
    }

    /**
     * 文档级标题字号收集：全部 TitleNode 非 null fontSize 去重降序——
     * 排名即标题层级（最大字号 = 0 级，决策 D3）。
     */
    private static List<Float> titleFontSizes(List<PageNode> pages) {
        return pages.stream()
                .filter(Objects::nonNull)
                .map(PageNode::getNodes)
                .filter(Objects::nonNull)
                .flatMap(List::stream)
                .filter(TitleNode.class::isInstance)
                .map(TitleNode.class::cast)
                .map(TitleNode::getFontSize)
                .filter(Objects::nonNull)
                .distinct()
                .sorted(Comparator.reverseOrder())
                .toList();
    }

    /**
     * 结构切块入口：null AST / 空页 → 空列表；产出未编号块种子
     * （chunkIndex/totalChunks 由 HybridSemanticChunker 统一编号）。
     *
     * @param ast 文档 AST（可 null）
     * @return 块种子列表
     */
    public List<ChunkSeed> chunk(DocumentAst ast) {
        if (ast == null || ast.getPages() == null) {
            return List.of();
        }
        ChunkingState state = new ChunkingState(titleFontSizes(ast.getPages()));
        List<ChunkSeed> seeds = new ArrayList<>();
        for (PageNode page : ast.getPages()) {
            if (page != null) {
                processPage(page, state, seeds);
            }
        }
        flushParagraph(state, seeds);
        log.info("结构切块完成 pages={}, seeds={}", ast.getPages().size(), seeds.size());
        return seeds;
    }

    /**
     * 单页处理：nodes 阅读序逐节点分派；页末按页级互斥规则处理 KVs（决策 D1）。
     */
    private void processPage(PageNode page, ChunkingState state, List<ChunkSeed> out) {
        TableFlags flags = new TableFlags();
        if (page.getNodes() != null) {
            for (DocumentNode node : page.getNodes()) {
                processNode(node, page, state, flags, out);
            }
        }
        processPageKeyValues(page, state, flags, out);
    }

    /**
     * 节点分派：标题/段落/表格走专属处理；其余类型跳过（决策 D2/D9）。
     */
    private void processNode(DocumentNode node, PageNode page, ChunkingState state,
                             TableFlags flags, List<ChunkSeed> out) {
        if (node instanceof TitleNode title) {
            processTitle(title, state, out);
        } else if (node instanceof ParagraphNode paragraph) {
            accumulateParagraph(paragraph, page, state, out);
        } else if (node instanceof TableNode table) {
            processTable(table, page, state, flags, out);
        }
        // HEADER/FOOTER：阶段 10 重分类非正文；IMAGE/STAMP/SIGNATURE：占位；LIST：预留（D9）
    }

    /**
     * 标题处理：冲刷段落缓冲 → 弹出层级 ≥ 当前的祖先 → 压入标题文本。
     * 空白标题跳过；fontSize 为 null 视为最末层级（防御）。
     */
    private void processTitle(TitleNode title, ChunkingState state, List<ChunkSeed> out) {
        String text = title.getText();
        if (text == null || text.isBlank()) {
            return;
        }
        flushParagraph(state, out);
        int level = state.levelOf(title.getFontSize());
        while (!state.levelStack.isEmpty() && state.levelStack.getLast() >= level) {
            state.levelStack.removeLast();
            state.titleStack.removeLast();
        }
        state.levelStack.add(level);
        state.titleStack.add(text.replace('\n', ' ').strip());
    }

    /**
     * 段落聚合：预算内入缓冲；超预算先冲刷再开新缓冲；段落自身超预算 →
     * 冲刷后独立交 SemanticChunker 句子/token 降级（验收点 1：段落原子不截断）。
     */
    private void accumulateParagraph(ParagraphNode paragraph, PageNode page,
                                     ChunkingState state, List<ChunkSeed> out) {
        String text = paragraph.getText();
        if (text == null || text.isBlank()) {
            return;
        }
        int tokens = tokenCounter.count(text);
        if (tokens > chunkSize) {
            flushParagraph(state, out);
            ChunkSeed meta = paragraphSeedMeta(paragraph, page, state.titlePath());
            for (ChunkSeed seed : semanticChunker.splitParagraph(text)) {
                out.add(seed.withMeta(meta));
            }
            return;
        }
        if (state.buffer.tokens() + tokens > chunkSize && !state.buffer.isEmpty()) {
            flushParagraph(state, out);
        }
        state.buffer.add(text, tokens, page.getPageNumber(), state.titlePath(),
                paragraph.getBbox(), paragraph.getConfidence(), paragraph.getSource());
    }

    /**
     * 表格处理：冲刷段落缓冲 → SemanticChunker 整表/行组切块（验收点 4/5）；
     * 记录页级表格形态供 KV 互斥判断（决策 D1）。
     */
    private void processTable(TableNode table, PageNode page, ChunkingState state,
                              TableFlags flags, List<ChunkSeed> out) {
        flushParagraph(state, out);
        List<ChunkSeed> tableSeeds = semanticChunker.splitTable(table);
        if (tableSeeds.isEmpty()) {
            return;
        }
        ChunkSeed meta = tableSeedMeta(table, page, state.titlePath());
        for (ChunkSeed seed : tableSeeds) {
            out.add(seed.withMeta(meta));
        }
        if (tableSeeds.size() > 1) {
            flags.hasLargeTable = true;
        } else {
            flags.hasFullTable = true;
        }
    }

    /**
     * 页末键值处理（决策 D1，验收点 3）：整块表格页 → 互斥跳过（硬约束 B
     * 表格优先）；大表拆分页 → KVs 整体成 KEY_VALUE 块补偿；无表格页的
     * 独立 KV 来源防御性成块。
     */
    private void processPageKeyValues(PageNode page, ChunkingState state,
                                      TableFlags flags, List<ChunkSeed> out) {
        if (flags.hasFullTable && !flags.hasLargeTable) {
            return;
        }
        List<KeyValueNode> keyValues = page.getKeyValues();
        if (keyValues == null || keyValues.isEmpty()) {
            return;
        }
        ChunkSeed meta = kvSeedMeta(keyValues, page, state.titlePath());
        for (ChunkSeed seed : semanticChunker.splitKeyValues(keyValues)) {
            out.add(seed.withMeta(meta));
        }
    }

    /**
     * 冲刷段落缓冲（空缓冲无操作）。
     */
    private void flushParagraph(ChunkingState state, List<ChunkSeed> out) {
        if (!state.buffer.isEmpty()) {
            out.add(state.buffer.flush());
        }
    }

    /**
     * 超长段落降级块的元数据模板：单段落节点的页/bbox/置信度/来源。
     */
    private ChunkSeed paragraphSeedMeta(ParagraphNode paragraph, PageNode page, String titlePath) {
        return new ChunkSeed(null, null, page.getPageNumber(), page.getPageNumber(), titlePath,
                formatBbox(paragraph.getBbox()), paragraph.getConfidence(),
                sourceName(paragraph.getSource()));
    }

    /**
     * 表格块的元数据模板：表格节点页/bbox/来源（TableNode.confidence 恒 null，
     * "块内节点"口径不下卷至单元格级，见计划 3.3 聚合规则）。
     */
    private ChunkSeed tableSeedMeta(TableNode table, PageNode page, String titlePath) {
        return new ChunkSeed(null, null, page.getPageNumber(), page.getPageNumber(), titlePath,
                formatBbox(table.getBbox()), table.getConfidence(),
                sourceName(table.getSource()));
    }

    /**
     * 键值块的元数据模板：KVs 聚合（bbox 并集 / 最小置信度 / 来源去重），
     * 页码为所在页，标题路径取页末状态（KV 属当前标题域）。
     */
    private ChunkSeed kvSeedMeta(List<KeyValueNode> keyValues, PageNode page, String titlePath) {
        List<BoundingBox> bboxes = new ArrayList<>();
        List<Float> confidences = new ArrayList<>();
        Set<String> sources = new LinkedHashSet<>();
        for (KeyValueNode kv : keyValues) {
            if (kv == null) {
                continue;
            }
            if (kv.getBbox() != null) {
                bboxes.add(kv.getBbox());
            }
            if (kv.getConfidence() != null) {
                confidences.add(kv.getConfidence());
            }
            sources.add(sourceName(kv.getSource()));
        }
        return new ChunkSeed(null, null, page.getPageNumber(), page.getPageNumber(), titlePath,
                unionBbox(bboxes), minConfidence(confidences), String.join(",", sources));
    }

    /**
     * 页内表格形态记录（页级 KV 互斥判断依据，决策 D1）。
     */
    private static final class TableFlags {

        /**
         * 页内存在整块成 TABLE 块的表格（该页 KV 互斥跳过）
         */
        private boolean hasFullTable;

        /**
         * 页内存在被行组拆分的大表格（该页 KV 补偿成块）
         */
        private boolean hasLargeTable;
    }

    /**
     * 切块状态（文档级）：标题层级基准 + titlePath 栈 + 段落聚合缓冲。
     */
    private final class ChunkingState {

        /**
         * 标题字号降序去重（排名 = 层级）
         */
        private final List<Float> titleFontSizes;

        /**
         * 标题路径栈（祖先 → 当前）
         */
        private final List<String> titleStack = new ArrayList<>();

        /**
         * 与 titleStack 对应的层级栈
         */
        private final List<Integer> levelStack = new ArrayList<>();

        /**
         * 段落聚合缓冲（跨页连续）
         */
        private final ParagraphBuffer buffer = new ParagraphBuffer();

        private ChunkingState(List<Float> titleFontSizes) {
            this.titleFontSizes = titleFontSizes;
        }

        /**
         * fontSize → 层级：降序列表排名；null 视为最末层级（防御）。
         */
        private int levelOf(Float fontSize) {
            if (fontSize == null) {
                return titleFontSizes.size();
            }
            int index = titleFontSizes.indexOf(fontSize);
            return index >= 0 ? index : titleFontSizes.size();
        }

        /**
         * 当前标题路径：" / " 连接祖先标题；无标题为空串。
         */
        private String titlePath() {
            return String.join(" / ", titleStack);
        }
    }

    /**
     * 段落聚合缓冲：贪心累积段落（token 预算由调用方判定），冲刷时聚合
     * bbox 并集 / 最小置信度 / 来源去重 / 跨页范围。段落间以空行连接
     * （GFM 段落分隔语义）。
     */
    private final class ParagraphBuffer {

        private final List<String> texts = new ArrayList<>();
        private final List<BoundingBox> bboxes = new ArrayList<>();
        private final List<Float> confidences = new ArrayList<>();
        private final Set<String> sources = new LinkedHashSet<>();
        private int tokens;
        private Integer pageStart;
        private Integer pageEnd;
        private String titlePath = "";

        private boolean isEmpty() {
            return texts.isEmpty();
        }

        private int tokens() {
            return tokens;
        }

        /**
         * 追加段落（调用方保证预算）；跨页范围取最小/最大页码。
         */
        private void add(String text, int tokenCount, int pageNumber, String titlePath,
                         BoundingBox bbox, Float confidence, ElementSource source) {
            if (texts.isEmpty()) {
                this.titlePath = titlePath;
            }
            texts.add(text);
            // 段落间空行分隔的 token 近似开销（保守 +2/分隔）
            tokens += tokenCount + (texts.size() > 1 ? 2 : 0);
            pageStart = pageStart == null ? pageNumber : Math.min(pageStart, pageNumber);
            pageEnd = pageEnd == null ? pageNumber : Math.max(pageEnd, pageNumber);
            if (bbox != null) {
                bboxes.add(bbox);
            }
            if (confidence != null) {
                confidences.add(confidence);
            }
            if (source != null) {
                sources.add(source.name());
            }
        }

        /**
         * 冲刷为 PARAGRAPH 块种子并重置缓冲。
         */
        private ChunkSeed flush() {
            ChunkSeed seed = new ChunkSeed(String.join("\n\n", texts), ChunkType.PARAGRAPH,
                    pageStart, pageEnd, titlePath, unionBbox(bboxes),
                    minConfidence(confidences), String.join(",", sources));
            texts.clear();
            bboxes.clear();
            confidences.clear();
            sources.clear();
            tokens = 0;
            pageStart = null;
            pageEnd = null;
            titlePath = "";
            return seed;
        }
    }
}
