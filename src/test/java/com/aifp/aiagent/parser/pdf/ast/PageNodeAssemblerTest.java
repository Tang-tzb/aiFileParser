package com.aifp.aiagent.parser.pdf.ast;

import com.aifp.aiagent.parser.pdf.PageContentType;
import com.aifp.aiagent.parser.pdf.layout.TableCell;
import com.aifp.aiagent.parser.pdf.layout.TableGrid;
import com.aifp.aiagent.parser.pdf.page.ElementSource;
import com.aifp.aiagent.parser.pdf.page.PageDocument;
import com.aifp.aiagent.parser.pdf.page.PageElement;
import com.aifp.aiagent.parser.pdf.page.PageElementType;
import com.aifp.aiagent.parser.pdf.region.RegionType;
import com.aifp.aiagent.parser.pdf.text.BoundingBox;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link PageNodeAssembler} 单元测试（阶段 9）：
 * 元素分派（标题/段落/占位）、OCR 置信度归一、约束 1（确定性阅读序）、
 * 约束 2（KeyValueNode 不进 nodes、表格进 nodes）、空页兜底。
 *
 * @author Tang_tzb
 */
class PageNodeAssemblerTest {

    private final PageNodeAssembler assembler = AstTestSupport.buildPageNodeAssembler();

    // ---------- 约束 1：确定性阅读序 ----------

    private static String textOf(DocumentNode node) {
        if (node instanceof TitleNode title) {
            return title.getText();
        }
        if (node instanceof ParagraphNode paragraph) {
            return paragraph.getText();
        }
        return null;
    }

    @Test
    void nodes_sortedByReadingOrder_topDownThenLeftRight_nullBboxLast() {
        // 输入乱序：右同顶、无 bbox、次高、左同顶、最高
        // 期望输出：自上而下（top=y+height 降序）→ 同顶自左向右（x 升序）→ 无 bbox 殿后
        PageElement top = AstTestSupport.textElement("top block", 12f, bbox(60, 500, 200, 20));
        PageElement second = AstTestSupport.textElement("second block", 12f, bbox(60, 480, 200, 30));
        PageElement leftSameTop = AstTestSupport.textElement("left", 12f, bbox(50, 300, 100, 20));
        PageElement rightSameTop = AstTestSupport.textElement("right", 12f, bbox(250, 300, 100, 20));
        PageElement noBbox = AstTestSupport.ocrElement("ocr line", 85f, null);
        PageDocument page = pageDocument(PageContentType.MIXED,
                List.of(rightSameTop, noBbox, second, leftSameTop, top));

        PageNode node = assembler.assemble(page);

        assertThat(node.getNodes()).hasSize(5);
        assertThat(node.getNodes())
                .extracting(PageNodeAssemblerTest::textOf)
                .containsExactly("top block", "second block", "left", "right", "ocr line");
    }

    // ---------- 约束 2：KeyValue 不重复输出 ----------

    @Test
    void nodes_sameTopSameX_keepExtractionOrder_stableSort() {
        // 同顶同 x 的两个节点维持提取序（List.sort 稳定排序 → 跨运行确定）
        PageElement first = AstTestSupport.textElement("first at same spot", 12f, bbox(60, 400, 200, 20));
        PageElement second = AstTestSupport.textElement("second at same spot", 12f, bbox(60, 400, 200, 20));
        PageDocument page = pageDocument(PageContentType.TEXT_ONLY, List.of(second, first));

        PageNode node = assembler.assemble(page);

        assertThat(node.getNodes())
                .extracting(PageNodeAssemblerTest::textOf)
                .containsExactly("second at same spot", "first at same spot");
    }

    @Test
    void keyValues_excludedFromNodes_tableKeptInNodes() {
        // 表格 → TableNode 进 nodes；FUSION 格 → KeyValueNode 只进 keyValues（不进 nodes），
        // 阶段 11 Markdown 只渲染 nodes，结构性杜绝键值重复
        PageElement paragraph = AstTestSupport.textElement("body text", 12f, bbox(60, 600, 200, 20));
        TableCell headerCell = AstTestSupport.cell(0, 0, 1, 1,
                "字段", null, ElementSource.OCR, 0.8f);
        TableCell fusionCell = AstTestSupport.cell(0, 1, 1, 1,
                "建设单位", "教育局", ElementSource.FUSION, 0.9f);
        TableGrid grid = AstTestSupport.grid(1, 2,
                List.of(AstTestSupport.row(0, List.of(headerCell, fusionCell))),
                List.of(headerCell, fusionCell));
        PageDocument page = pageDocument(PageContentType.MIXED,
                List.of(paragraph), List.of(grid));

        PageNode node = assembler.assemble(page);

        assertThat(node.getNodes())
                .extracting(DocumentNode::getType)
                .containsExactly(DocumentNodeType.PARAGRAPH, DocumentNodeType.TABLE);
        assertThat(node.getKeyValues()).hasSize(1);
        assertThat(node.getKeyValues().get(0).getKey()).isEqualTo("建设单位");
        assertThat(node.getKeyValues().get(0).getValue()).isEqualTo("教育局");
        assertThat(node.getKeyValues().get(0).getType()).isEqualTo(DocumentNodeType.KEY_VALUE);
    }

    // ---------- 元素分派 ----------

    @Test
    void tableWithoutFusionCells_noKeyValues() {
        // 无 FUSION 格（纯表头/纯值）→ keyValues 为空，表格仍进 nodes
        TableCell headerCell = AstTestSupport.cell(0, 0, 1, 1,
                "字段", null, ElementSource.OCR, 0.8f);
        TableCell valueCell = AstTestSupport.cell(0, 1, 1, 1,
                null, "纯文字值", ElementSource.PDF_TEXT, 1.0f);
        TableGrid grid = AstTestSupport.grid(1, 2,
                List.of(AstTestSupport.row(0, List.of(headerCell, valueCell))),
                List.of(headerCell, valueCell));

        PageNode node = assembler.assemble(pageDocument(
                PageContentType.MIXED, List.of(), List.of(grid)));

        assertThat(node.getKeyValues()).isEmpty();
        assertThat(node.getNodes()).extracting(DocumentNode::getType)
                .containsExactly(DocumentNodeType.TABLE);
    }

    @Test
    void pdfText_bigFontShortText_becomesTitleNode() {
        // 16pt ≥ 12pt×1.2 → TitleNode（source=PDF_TEXT、confidence=1.0）
        // 三个 12pt 正文 + 一个 16pt 标题 → 中位字号=12（偶数个取上中位）
        PageElement title = AstTestSupport.textElement("施工许可证", 16f, bbox(60, 700, 200, 24));
        PageElement body1 = AstTestSupport.textElement("正文段落一", 12f, bbox(60, 600, 200, 20));
        PageElement body2 = AstTestSupport.textElement("正文段落二", 12f, bbox(60, 500, 200, 20));
        PageElement body3 = AstTestSupport.textElement("正文段落三", 12f, bbox(60, 400, 200, 20));

        PageNode node = assembler.assemble(pageDocument(
                PageContentType.TEXT_ONLY, List.of(title, body1, body2, body3)));

        assertThat(node.getNodes().get(0)).isInstanceOf(TitleNode.class);
        TitleNode titleNode = (TitleNode) node.getNodes().get(0);
        assertThat(titleNode.getText()).isEqualTo("施工许可证");
        assertThat(titleNode.getFontSize()).isEqualTo(16f);
        assertThat(titleNode.getFontName()).isEqualTo("Helvetica");
        assertThat(titleNode.getSource()).isEqualTo(ElementSource.PDF_TEXT);
        assertThat(titleNode.getConfidence()).isEqualTo(1.0f);
        // 正文 → 段落
        assertThat(node.getNodes().get(1)).isInstanceOf(ParagraphNode.class);
    }

    @Test
    void ocrElement_paragraphWithNormalizedConfidence() {
        // OCR 置信度 0~100 → 归一 0~1；无字号/字体
        PageElement ocr = AstTestSupport.ocrElement("扫描文字行", 85f, bbox(60, 400, 200, 20));

        PageNode node = assembler.assemble(pageDocument(
                PageContentType.IMAGE_ONLY, List.of(ocr)));

        ParagraphNode paragraph = (ParagraphNode) node.getNodes().get(0);
        assertThat(paragraph.getSource()).isEqualTo(ElementSource.OCR);
        assertThat(paragraph.getConfidence()).isEqualTo(0.85f);
        assertThat(paragraph.getFontSize()).isNull();
        assertThat(paragraph.getFontName()).isNull();
    }

    @Test
    void ocrOnlyPage_neverTitle() {
        // OCR 独页：中位字号=0 兜底拒绝 → 恒为段落（永不判题）
        PageElement ocr = AstTestSupport.ocrElement("big looking ocr title", 95f, bbox(60, 400, 200, 20));

        PageNode node = assembler.assemble(pageDocument(
                PageContentType.IMAGE_ONLY, List.of(ocr)));

        assertThat(node.getNodes().get(0).getType()).isEqualTo(DocumentNodeType.PARAGRAPH);
    }

    // ---------- 兜底 ----------

    @Test
    void regionElements_mappedToStampSignatureImage() {
        // STAMP→STAMP、SIGNATURE→SIGNATURE、其余（含未恢复 TABLE 区域）→IMAGE 占位
        PageElement stamp = AstTestSupport.regionElement(
                PageElementType.IMAGE_REGION, RegionType.STAMP, "红色印章区域", bbox(400, 600, 80, 80));
        PageElement signature = AstTestSupport.regionElement(
                PageElementType.IMAGE_REGION, RegionType.SIGNATURE, "签名区域", bbox(400, 500, 80, 40));
        PageElement tableRegion = AstTestSupport.regionElement(
                PageElementType.IMAGE_REGION, RegionType.TABLE, "未恢复表格区域", bbox(60, 200, 300, 150));

        PageNode node = assembler.assemble(pageDocument(
                PageContentType.MIXED, List.of(stamp, signature, tableRegion)));

        assertThat(node.getNodes())
                .extracting(DocumentNode::getType)
                .containsExactly(DocumentNodeType.STAMP, DocumentNodeType.SIGNATURE, DocumentNodeType.IMAGE);
        // 占位节点语义：source=IMAGE、confidence=null、description 透传
        DocumentNode stampNode = node.getNodes().get(0);
        assertThat(stampNode.getSource()).isEqualTo(ElementSource.IMAGE);
        assertThat(stampNode.getConfidence()).isNull();
        assertThat(stampNode.getDescription()).isEqualTo("红色印章区域");
    }

    @Test
    void nullElements_emptyNodesAndKeyValues() {
        // elements=null（防御）→ 空 nodes/keyValues
        PageNode node = assembler.assemble(pageDocument(PageContentType.EMPTY, null));

        assertThat(node.getNodes()).isEmpty();
        assertThat(node.getKeyValues()).isEmpty();
        assertThat(node.getContentType()).isEqualTo(PageContentType.EMPTY);
    }

    // ---------- 夹具 ----------

    @Test
    void unknownElementType_filteredOut() {
        // 类型缺失的元素被过滤，不产生节点
        PageElement broken = PageElement.builder()
                .source(ElementSource.PDF_TEXT)
                .text("orphan element")
                .bbox(bbox(60, 400, 200, 20))
                .build();

        PageNode node = assembler.assemble(pageDocument(
                PageContentType.TEXT_ONLY, List.of(broken)));

        assertThat(node.getNodes()).isEmpty();
    }

    private BoundingBox bbox(float x, float y, float width, float height) {
        return BoundingBox.builder().x(x).y(y).width(width).height(height).build();
    }

    private PageDocument pageDocument(PageContentType contentType, List<PageElement> elements) {
        return pageDocument(contentType, elements, List.of());
    }

    private PageDocument pageDocument(PageContentType contentType,
                                      List<PageElement> elements, List<TableGrid> tables) {
        return PageDocument.builder()
                .pageNumber(1)
                .contentType(contentType)
                .pageWidth(595f)
                .pageHeight(842f)
                .elements(elements)
                .tables(tables)
                .parserName("TestPageParser")
                .build();
    }
}
