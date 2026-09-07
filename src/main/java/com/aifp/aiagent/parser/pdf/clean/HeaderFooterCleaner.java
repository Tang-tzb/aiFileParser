package com.aifp.aiagent.parser.pdf.clean;

import com.aifp.aiagent.parser.pdf.ast.DocumentNode;
import com.aifp.aiagent.parser.pdf.ast.DocumentNodeType;
import com.aifp.aiagent.parser.pdf.ast.PageNode;
import com.aifp.aiagent.parser.pdf.ast.ParagraphNode;
import com.aifp.aiagent.parser.pdf.text.BoundingBox;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 页眉页脚清洗器（阶段 10，对应《PDF解析改造方案》§十七）。
 * <p>
 * 判定三要素（全部满足才重分类，保守策略）：
 * <ul>
 *   <li><b>相同内容</b>：分组键 = {@link CharacterCleaner#normalize}（空白/换行差异
 *       不算内容差异）；</li>
 *   <li><b>固定带位</b>：PDF 用户空间（原点左下、无旋转，硬约束 2）——距页顶 =
 *       pageHeight − (y+height)，距页底 = y；两带同时命中视为歧义（保守跳过），
 *       禁止引入 DPI/像素换算；</li>
 *   <li><b>跨页重复</b>：出现于 ≥ {@code min-pages} 个不同页。</li>
 * </ul>
 * 重分类以<b>重建新节点</b>表达（硬约束 1）：候选段重建为基类
 * {@code DocumentNode(HEADER/FOOTER)}，description 承载清洗后文本（D3），
 * source/confidence/bbox 原样透传；节点留在 nodes 原位（阅读序不变，
 * 阶段 11/12 按类型过滤正文）。无 bbox/空白文本/非段落类型不参与。
 *
 * @author Tang_tzb
 */
@Component
public class HeaderFooterCleaner {

    private final CharacterCleaner characterCleaner;
    /**
     * 跨页重复判定最小页数（配置 {@code clean.header-footer.min-pages}）
     */
    @Value("${document.parser.pdf.clean.header-footer.min-pages:2}")
    private int minPages = 2;
    /**
     * 顶部带位比例：距页顶距离 ÷ 页高 ≤ 该值视为页顶带
     */
    @Value("${document.parser.pdf.clean.header-footer.top-band-ratio:0.12}")
    private double topBandRatio = 0.12;
    /**
     * 底部带位比例：距页底距离（bbox.y）÷ 页高 ≤ 该值视为页底带
     */
    @Value("${document.parser.pdf.clean.header-footer.bottom-band-ratio:0.12}")
    private double bottomBandRatio = 0.12;

    public HeaderFooterCleaner(CharacterCleaner characterCleaner) {
        this.characterCleaner = characterCleaner;
    }

    /**
     * 跨页重复段重分类入口（§十七）。
     *
     * @param pages 页节点列表（可 null；本方法绝不修改输入）
     * @return 重分类后的新页列表；页数不足 {@code minPages} 或无命中时原列表返回
     */
    public List<PageNode> reclassify(List<PageNode> pages) {
        if (pages == null || pages.size() < minPages) {
            return pages;
        }
        Map<Slot, DocumentNodeType> marks = decide(collect(pages));
        if (marks.isEmpty()) {
            return pages;
        }
        return rebuild(pages, marks);
    }

    /**
     * 第一遍扫描：收集全部候选段出现（含中部带——用于"混带跳过"判定）。
     */
    private Map<String, List<Occurrence>> collect(List<PageNode> pages) {
        Map<String, List<Occurrence>> groups = new HashMap<>();
        for (int pi = 0; pi < pages.size(); pi++) {
            PageNode page = pages.get(pi);
            if (page == null || page.getPageHeight() <= 0) {
                continue;
            }
            collectPage(groups, pi, page);
        }
        return groups;
    }

    /**
     * 单页候选收集：仅 PARAGRAPH 且文本非空白、bbox 非空。
     */
    private void collectPage(Map<String, List<Occurrence>> groups, int pageIndex, PageNode page) {
        List<DocumentNode> nodes = page.getNodes();
        for (int ni = 0; ni < nodes.size(); ni++) {
            if (!(nodes.get(ni) instanceof ParagraphNode paragraph) || !isCandidate(paragraph)) {
                continue;
            }
            String key = characterCleaner.normalize(paragraph.getText());
            if (key == null || key.isEmpty()) {
                continue;
            }
            groups.computeIfAbsent(key, k -> new ArrayList<>())
                    .add(new Occurrence(new Slot(pageIndex, ni), paragraph,
                            bandOf(paragraph.getBbox(), page.getPageHeight())));
        }
    }

    /**
     * 候选门槛：文本非空白 + 有 bbox（无位不判带）。
     */
    private boolean isCandidate(ParagraphNode node) {
        return node.getText() != null && !node.getText().isBlank() && node.getBbox() != null;
    }

    /**
     * 带位判定（PDF 用户空间，原点左下）：距页顶 = pageHeight − (y+height)，
     * 距页底 = y；两带同时命中视为歧义 → OTHER（保守跳过，硬约束 2）。
     */
    private Band bandOf(BoundingBox bbox, float pageHeight) {
        boolean top = (pageHeight - bbox.top()) / pageHeight <= topBandRatio;
        boolean bottom = bbox.getY() / pageHeight <= bottomBandRatio;
        if (top && !bottom) {
            return Band.TOP;
        }
        if (bottom && !top) {
            return Band.BOTTOM;
        }
        return Band.OTHER;
    }

    /**
     * 组判定：全部出现同带且跨页数 ≥ minPages → HEADER/FOOTER；否则无标记（保守跳过）。
     */
    private Map<Slot, DocumentNodeType> decide(Map<String, List<Occurrence>> groups) {
        Map<Slot, DocumentNodeType> marks = new HashMap<>();
        for (List<Occurrence> occurrences : groups.values()) {
            DocumentNodeType type = groupType(occurrences);
            if (type == null) {
                continue;
            }
            occurrences.forEach(o -> marks.put(o.slot(), type));
        }
        return marks;
    }

    /**
     * 单组类型判定：首出现带位为基准，混带/中部/跨页不足均返回 null。
     */
    private DocumentNodeType groupType(List<Occurrence> occurrences) {
        Band band = occurrences.get(0).band();
        if (band == Band.OTHER) {
            return null;
        }
        long distinctPages = occurrences.stream()
                .mapToLong(o -> o.slot().pageIndex()).distinct().count();
        if (distinctPages < minPages
                || occurrences.stream().anyMatch(o -> o.band() != band)) {
            return null;
        }
        return band == Band.TOP ? DocumentNodeType.HEADER : DocumentNodeType.FOOTER;
    }

    /**
     * 第二遍重建：命中位替换为基类占位节点（description=清洗后文本），
     * 其余节点与未命中页原引用透传；keyValues 原样保留。
     */
    private List<PageNode> rebuild(List<PageNode> pages, Map<Slot, DocumentNodeType> marks) {
        List<PageNode> result = new ArrayList<>(pages.size());
        for (int pi = 0; pi < pages.size(); pi++) {
            result.add(rebuildPage(pages.get(pi), pi, marks));
        }
        return result;
    }

    /**
     * 单页重建：无命中页返回原实例；有命中页经 builder 组装新 PageNode。
     */
    private PageNode rebuildPage(PageNode page, int pageIndex, Map<Slot, DocumentNodeType> marks) {
        List<DocumentNode> nodes = page.getNodes();
        List<DocumentNode> newNodes = null;
        for (int ni = 0; ni < nodes.size(); ni++) {
            DocumentNodeType type = marks.get(new Slot(pageIndex, ni));
            if (type == null) {
                continue;
            }
            if (newNodes == null) {
                newNodes = new ArrayList<>(nodes);
            }
            ParagraphNode paragraph = (ParagraphNode) nodes.get(ni);
            // 重分类为基类占位节点（D3）：其余字段零改动透传（硬约束 2）
            newNodes.set(ni, new DocumentNode(type, paragraph.getSource(),
                    paragraph.getConfidence(), paragraph.getBbox(), paragraph.getText()));
        }
        if (newNodes == null) {
            return page;
        }
        return PageNode.builder()
                .pageNumber(page.getPageNumber())
                .contentType(page.getContentType())
                .pageWidth(page.getPageWidth())
                .pageHeight(page.getPageHeight())
                .nodes(newNodes)
                .keyValues(page.getKeyValues())
                .build();
    }

    /**
     * 带位枚举：页顶带 / 页底带 / 其他（中部或带位歧义）
     */
    private enum Band {TOP, BOTTOM, OTHER}

    /**
     * 重分类标记定位：页序号 + 节点序号（重建时按位替换）
     */
    private record Slot(int pageIndex, int nodeIndex) {
    }

    /**
     * 候选出现记录：定位 + 节点引用 + 带位
     */
    private record Occurrence(Slot slot, ParagraphNode node, Band band) {
    }
}
