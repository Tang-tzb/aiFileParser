package com.aifp.aiagent.parser.pdf.clean;

import com.aifp.aiagent.parser.pdf.PageContentType;
import com.aifp.aiagent.parser.pdf.ast.*;
import com.aifp.aiagent.parser.pdf.page.ElementSource;
import com.aifp.aiagent.parser.pdf.text.BoundingBox;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link HeaderFooterCleaner} 单元测试（阶段 10）：
 * 跨页重复 + 固定带位 + 最小页数三要素、混带保守跳过、
 * PDF 用户空间带位判定（无坐标翻转/换算）、bbox 引用透传（硬约束 2）。
 *
 * @author Tang_tzb
 */
class HeaderFooterCleanerTest {

    private static final float PAGE_SIZE = 1000f;

    private static int bodySeq = 0;

    private HeaderFooterCleaner cleaner;

    @BeforeEach
    void setUp() {
        cleaner = new HeaderFooterCleaner(new CharacterCleaner());
        ReflectionTestUtils.setField(cleaner, "minPages", 2);
        ReflectionTestUtils.setField(cleaner, "topBandRatio", 0.12);
        ReflectionTestUtils.setField(cleaner, "bottomBandRatio", 0.12);
    }

    @Test
    void nullPages_passthrough() {
        assertThat(cleaner.reclassify(null)).isNull();
    }

    @Test
    void singlePage_belowMinPages_skip() {
        // 页数 < min-pages：不做页眉页脚判定
        List<PageNode> pages = List.of(page(1, List.of(
                paragraph("第 1 页 共 1 页", topBbox()))));

        List<PageNode> result = cleaner.reclassify(pages);

        assertThat(result).isSameAs(pages);
        assertThat(result.get(0).getNodes().get(0).getType()).isEqualTo(DocumentNodeType.PARAGRAPH);
    }

    @Test
    void sameTextTopBand_multiPages_reclassifiedAsHeader() {
        // 3 页同顶同文 → HEADER；重分类节点 description 承载文本，
        // source/confidence/bbox 零改动透传（硬约束 2）
        ParagraphNode first = new ParagraphNode("第 1 页 共 3 页",
                ElementSource.OCR, 0.85f, topBbox(), null, null);
        List<PageNode> pages = List.of(
                page(1, new ArrayList<>(List.of(first, bodyParagraph()))),
                page(2, List.of(paragraph("第 1 页 共 3 页", topBbox()), bodyParagraph())),
                page(3, List.of(paragraph("第 1 页 共 3 页", topBbox()), bodyParagraph())));

        List<PageNode> result = cleaner.reclassify(pages);

        DocumentNode header = result.get(0).getNodes().get(0);
        assertThat(header.getType()).isEqualTo(DocumentNodeType.HEADER);
        assertThat(header.getDescription()).isEqualTo("第 1 页 共 3 页");
        assertThat(header.getSource()).isEqualTo(ElementSource.OCR);
        assertThat(header.getConfidence()).isEqualTo(0.85f);
        assertThat(header.getBbox()).isSameAs(first.getBbox());
        assertThat(result.get(1).getNodes().get(0).getType()).isEqualTo(DocumentNodeType.HEADER);
        assertThat(result.get(2).getNodes().get(0).getType()).isEqualTo(DocumentNodeType.HEADER);
    }

    @Test
    void sameTextBottomBand_multiPages_reclassifiedAsFooter() {
        // 3 页同底同文 → FOOTER（带位映射正确性：y=20 距页底近 → FOOTER 而非 HEADER）
        List<PageNode> pages = List.of(
                page(1, List.of(paragraph("皖发改备案", bottomBbox()), bodyParagraph())),
                page(2, List.of(paragraph("皖发改备案", bottomBbox()), bodyParagraph())));

        List<PageNode> result = cleaner.reclassify(pages);

        assertThat(result.get(0).getNodes().get(0).getType()).isEqualTo(DocumentNodeType.FOOTER);
        assertThat(result.get(1).getNodes().get(0).getType()).isEqualTo(DocumentNodeType.FOOTER);
    }

    @Test
    void mixedBand_sameText_conservativeSkip() {
        // 混带（2 页顶带 + 1 页底带）→ 保守跳过，全部保持 PARAGRAPH
        List<PageNode> pages = List.of(
                page(1, List.of(paragraph("重复文本", topBbox()))),
                page(2, List.of(paragraph("重复文本", topBbox()))),
                page(3, List.of(paragraph("重复文本", bottomBbox()))));

        List<PageNode> result = cleaner.reclassify(pages);

        for (PageNode pageNode : result) {
            assertThat(pageNode.getNodes().get(0).getType()).isEqualTo(DocumentNodeType.PARAGRAPH);
        }
    }

    @Test
    void middleBand_sameText_skip() {
        // 中部带位（距页顶/页底均超比例）→ 不参与页眉页脚判定
        List<PageNode> pages = List.of(
                page(1, List.of(paragraph("正文重复", middleBbox()))),
                page(2, List.of(paragraph("正文重复", middleBbox()))));

        List<PageNode> result = cleaner.reclassify(pages);

        assertThat(result.get(0).getNodes().get(0).getType()).isEqualTo(DocumentNodeType.PARAGRAPH);
        assertThat(result.get(1).getNodes().get(0).getType()).isEqualTo(DocumentNodeType.PARAGRAPH);
    }

    @Test
    void repeatedOnSamePageOnly_skip() {
        // 同键仅出现在 1 页（同页重复 3 次）→ 跨页数不足，跳过
        List<PageNode> pages = List.of(
                page(1, List.of(paragraph("同页重复", topBbox()),
                        paragraph("同页重复", topBbox()),
                        paragraph("同页重复", topBbox()))),
                page(2, List.of(bodyParagraph())),
                page(3, List.of(bodyParagraph())));

        List<PageNode> result = cleaner.reclassify(pages);

        assertThat(result.get(0).getNodes().get(0).getType()).isEqualTo(DocumentNodeType.PARAGRAPH);
    }

    @Test
    void whitespaceDifference_sameGroupingKey() {
        // 分组键经 normalize：空白/换行差异不算内容差异
        List<PageNode> pages = List.of(
                page(1, List.of(paragraph("第 1 页\n共 3 页", topBbox()))),
                page(2, List.of(paragraph("第 1 页 共 3 页", topBbox()))));

        List<PageNode> result = cleaner.reclassify(pages);

        assertThat(result.get(0).getNodes().get(0).getType()).isEqualTo(DocumentNodeType.HEADER);
        assertThat(result.get(1).getNodes().get(0).getType()).isEqualTo(DocumentNodeType.HEADER);
    }

    @Test
    void titleNode_notParticipating() {
        // TITLE 类型不参与（防大字号重复标题误判为页眉）
        List<PageNode> pages = List.of(
                page(1, List.of(title("第一章 总则"))),
                page(2, List.of(title("第一章 总则"))));

        List<PageNode> result = cleaner.reclassify(pages);

        assertThat(result.get(0).getNodes().get(0).getType()).isEqualTo(DocumentNodeType.TITLE);
        assertThat(result.get(1).getNodes().get(0).getType()).isEqualTo(DocumentNodeType.TITLE);
    }

    @Test
    void noBboxParagraph_skip() {
        // 无 bbox 不判带
        ParagraphNode noBbox = new ParagraphNode("无位文本", ElementSource.OCR, 0.9f, null, null, null);
        List<PageNode> pages = List.of(
                page(1, List.of(noBbox)),
                page(2, List.of(new ParagraphNode("无位文本", ElementSource.OCR, 0.9f, null, null, null))));

        List<PageNode> result = cleaner.reclassify(pages);

        assertThat(result.get(0).getNodes().get(0).getType()).isEqualTo(DocumentNodeType.PARAGRAPH);
        assertThat(result.get(1).getNodes().get(0).getType()).isEqualTo(DocumentNodeType.PARAGRAPH);
    }

    @Test
    void rebuiltPage_structurePreserved_unchangedPageSameReference() {
        // 有命中页 = 新 PageNode（页元数据/keyValues 保留），未命中页 = 原引用，
        // 未命中节点 = 原引用（阅读序不变，仅按位替换）
        PageNode page1 = page(1, new ArrayList<>(List.of(
                paragraph("页眉文本", topBbox()), bodyParagraph())));
        PageNode page2 = page(2, new ArrayList<>(List.of(
                paragraph("页眉文本", topBbox()), bodyParagraph())));
        PageNode page3 = page(3, List.of(bodyParagraph()));

        List<PageNode> result = cleaner.reclassify(List.of(page1, page2, page3));

        assertThat(result.get(0)).isNotSameAs(page1);
        assertThat(result.get(0).getPageNumber()).isEqualTo(1);
        assertThat(result.get(0).getPageWidth()).isEqualTo(PAGE_SIZE);
        assertThat(result.get(0).getPageHeight()).isEqualTo(PAGE_SIZE);
        assertThat(result.get(0).getContentType()).isEqualTo(PageContentType.TEXT_ONLY);
        assertThat(result.get(0).getKeyValues()).isSameAs(page1.getKeyValues());
        assertThat(result.get(0).getNodes().get(1)).isSameAs(page1.getNodes().get(1));
        assertThat(result.get(1)).isNotSameAs(page2);
        assertThat(result.get(2)).isSameAs(page3);
    }

    @Test
    void minPagesConfigurable_higherThresholdSkip() {
        // min-pages 提高到 3：2 页重复不再判定
        ReflectionTestUtils.setField(cleaner, "minPages", 3);
        List<PageNode> pages = List.of(
                page(1, List.of(paragraph("页眉文本", topBbox()))),
                page(2, List.of(paragraph("页眉文本", topBbox()))));

        List<PageNode> result = cleaner.reclassify(pages);

        assertThat(result.get(0).getNodes().get(0).getType()).isEqualTo(DocumentNodeType.PARAGRAPH);
    }

    @Test
    void bandMath_noCoordinateFlip() {
        // 非对称 bbox 证明无坐标翻转：同页"高 y 文本"判顶带、"低 y 文本"判底带
        // （若实现发生翻转，二者将互判为 FOOTER/HEADER）
        ParagraphNode topText = paragraph("顶部文本", bbox(100, 950, 300, 30));
        ParagraphNode bottomText = paragraph("底部文本", bbox(100, 20, 300, 580));
        List<PageNode> pages = List.of(
                page(1, List.of(topText, bottomText)),
                page(2, List.of(paragraph("顶部文本", bbox(100, 950, 300, 30)),
                        paragraph("底部文本", bbox(100, 20, 300, 580)))));

        List<PageNode> result = cleaner.reclassify(pages);

        assertThat(result.get(0).getNodes().get(0).getType()).isEqualTo(DocumentNodeType.HEADER);
        assertThat(result.get(0).getNodes().get(1).getType()).isEqualTo(DocumentNodeType.FOOTER);
    }

    // ---------- 夹具 ----------

    /**
     * 顶带段 bbox：y=950, h=30 → 距页顶 = 1000−980 = 20 ≤ 120，且 y=950 非底带
     */
    private BoundingBox topBbox() {
        return bbox(100, 950, 300, 30);
    }

    /**
     * 底带段 bbox：y=20, h=30 → 距页底 = 20 ≤ 120，且距页顶 = 950 非顶带
     */
    private BoundingBox bottomBbox() {
        return bbox(100, 20, 300, 30);
    }

    /**
     * 中部段 bbox：距页顶 570 / 距页底 400，均超 0.12×1000
     */
    private BoundingBox middleBbox() {
        return bbox(100, 400, 300, 30);
    }

    private ParagraphNode paragraph(String text, BoundingBox bbox) {
        return new ParagraphNode(text, ElementSource.PDF_TEXT, 1.0f, bbox, 10f, "SimSun");
    }

    /**
     * 正文段：文本递增编号避免与页眉页脚用例文本意外同键分组
     */
    private ParagraphNode bodyParagraph() {
        return paragraph("正文内容" + bodySeq++, middleBbox());
    }

    private TitleNode title(String text) {
        return new TitleNode(text, 16f, "SimHei", topBbox());
    }

    private PageNode page(int pageNumber, List<DocumentNode> nodes) {
        return PageNode.builder()
                .pageNumber(pageNumber)
                .contentType(PageContentType.TEXT_ONLY)
                .pageWidth(PAGE_SIZE)
                .pageHeight(PAGE_SIZE)
                .nodes(new ArrayList<>(nodes))
                .keyValues(List.of())
                .build();
    }

    private BoundingBox bbox(float x, float y, float width, float height) {
        return BoundingBox.builder().x(x).y(y).width(width).height(height).build();
    }
}
