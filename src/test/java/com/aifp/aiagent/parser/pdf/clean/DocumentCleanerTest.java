package com.aifp.aiagent.parser.pdf.clean;

import com.aifp.aiagent.parser.pdf.PageContentType;
import com.aifp.aiagent.parser.pdf.PdfContentType;
import com.aifp.aiagent.parser.pdf.ast.*;
import com.aifp.aiagent.parser.pdf.page.ElementSource;
import com.aifp.aiagent.parser.pdf.text.BoundingBox;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link DocumentCleaner} 单元测试（阶段 10）：
 * 6 步流水线顺序（断行数字先接全再纠错）、§十六 验收案例（多行归一单一 Cell/Paragraph）、
 * 输入 AST 不可变（硬约束 1）、bbox 引用透传（硬约束 2）、
 * 页眉页脚重分类与去重端到端、KV 单一事实源重建。
 *
 * @author Tang_tzb
 */
class DocumentCleanerTest {

    private DocumentCleaner cleaner;

    @BeforeEach
    void setUp() {
        CharacterCleaner characterCleaner = new CharacterCleaner();
        LineCleaner lineCleaner = new LineCleaner();
        OcrErrorCleaner ocrErrorCleaner = new OcrErrorCleaner();
        cleaner = new DocumentCleaner(characterCleaner, lineCleaner, ocrErrorCleaner,
                new TableCleaner(characterCleaner, lineCleaner, ocrErrorCleaner),
                new HeaderFooterCleaner(characterCleaner),
                new DuplicateCleaner(characterCleaner));
    }

    @Test
    void nullAst_passthrough() {
        assertThat(cleaner.clean(null)).isNull();
    }

    @Test
    void pipelineOrder_ocrBrokenDigitJoinedThenFixed() {
        // 步骤顺序实证：行连接先于 OCR 纠错（17O\n649.08 接全 → 纠错 → 170649.08）
        DocumentAst ast = ast(page(1, List.of(
                ocrParagraph("17O\n649.08"))));

        DocumentAst cleaned = cleaner.clean(ast);

        assertThat(firstParagraphText(cleaned)).isEqualTo("170649.08");
    }

    @Test
    void pdfTextParagraph_neverOcrFixed() {
        // PDF_TEXT 零损耗：数字形 O 语境不纠正（§十八：仅 OCR 源）
        DocumentAst ast = ast(page(1, List.of(
                pdfParagraph("编号 17O649.08 备案"))));

        DocumentAst cleaned = cleaner.clean(ast);

        assertThat(firstParagraphText(cleaned)).isEqualTo("编号 17O649.08 备案");
    }

    @Test
    void acceptanceCase_paragraphMultiLineJoinedToOne() {
        // §十六 验收：多行段落 → 单一 Paragraph 单行
        DocumentAst ast = ast(page(1, List.of(pdfParagraph(
                "南地块位于养生大道以南，\n古井大道以东；\n"
                        + "北地块位于谯城经开区古井大道以东，\n桐花路（原创业西路）以南"))));

        DocumentAst cleaned = cleaner.clean(ast);

        assertThat(firstParagraphText(cleaned)).isEqualTo(
                "南地块位于养生大道以南，古井大道以东；"
                        + "北地块位于谯城经开区古井大道以东，桐花路（原创业西路）以南");
    }

    @Test
    void acceptanceCase_tableCellMultiLineToOne_andKvSynced() {
        // §十六 验收：多行 Cell → 单一 Cell 单行；KV 从清洗后 cells 重建（单一事实源）
        TableCellNode cell = new TableCellNode(0, 1, 1, 1, "建 设 地 点",
                "南地块位于养生大道以南，\n古井大道以东；北地块位于谯城经开区古井大道以东，\n"
                        + "桐花路（原创业西路）以南",
                ElementSource.FUSION, 0.9f, bbox());
        DocumentAst ast = ast(page(1, List.of(table(List.of(cell)))));

        DocumentAst cleaned = cleaner.clean(ast);

        TableCellNode cleanedCell = firstTableCell(cleaned);
        assertThat(cleanedCell.getHeader()).isEqualTo("建设地点");
        assertThat(cleanedCell.getValue()).isEqualTo(
                "南地块位于养生大道以南，古井大道以东；"
                        + "北地块位于谯城经开区古井大道以东，桐花路（原创业西路）以南");
        List<KeyValueNode> kvs = cleaned.getPages().get(0).getKeyValues();
        assertThat(kvs).hasSize(1);
        assertThat(kvs.get(0).getKey()).isEqualTo("建设地点");
        assertThat(kvs.get(0).getValue()).isEqualTo(cleanedCell.getValue());
    }

    @Test
    void inputAst_immutable_afterClean() {
        // 硬约束 1：输入 AST 清洗前后内容完全不变
        ParagraphNode paragraph = pdfParagraph("多行\n段落");
        TitleNode title = new TitleNode("标\n题", 16f, "SimHei", bbox());
        TableCellNode cell = new TableCellNode(0, 0, 1, 1, "建 设 单 位",
                "亳州\n市教育局", ElementSource.FUSION, 0.9f, bbox());
        TableNode table = table(List.of(cell));
        PageNode originalPage = page(1, new ArrayList<>(List.of(paragraph, title, table)));
        DocumentAst ast = ast(originalPage);

        cleaner.clean(ast);

        assertThat(paragraph.getText()).isEqualTo("多行\n段落");
        assertThat(title.getText()).isEqualTo("标\n题");
        assertThat(cell.getHeader()).isEqualTo("建 设 单 位");
        assertThat(cell.getValue()).isEqualTo("亳州\n市教育局");
        assertThat(ast.getPages()).hasSize(1);
        assertThat(ast.getPages().get(0)).isSameAs(originalPage);
        assertThat(ast.getPages().get(0).getNodes()).hasSize(3);
    }

    @Test
    void bboxReferencePassthrough_noCoordinateTransform() {
        // 硬约束 2：重建节点透传原 bbox 引用，无坐标复制/变换
        BoundingBox paragraphBbox = bbox();
        BoundingBox tableBbox = BoundingBox.builder().x(60).y(300).width(400).height(100).build();
        ParagraphNode paragraph = new ParagraphNode("段落\n文本", ElementSource.PDF_TEXT,
                1.0f, paragraphBbox, 10f, "SimSun");
        TableNode table = new TableNode(1, 1, List.of(new TableRowNode(0, null,
                List.of(new TableCellNode(0, 0, 1, 1, "键", "值",
                        ElementSource.FUSION, 0.9f, paragraphBbox)))),
                ElementSource.FUSION, tableBbox);
        DocumentAst ast = ast(page(1, List.of(paragraph, table)));

        DocumentAst cleaned = cleaner.clean(ast);

        assertThat(cleaned.getPages().get(0).getNodes().get(0).getBbox())
                .isSameAs(paragraphBbox);
        assertThat(cleaned.getPages().get(0).getNodes().get(1).getBbox()).isSameAs(tableBbox);
        assertThat(cellBboxOf(cleaned, 1)).isSameAs(paragraphBbox);
    }

    @Test
    void documentLevelFields_preserved() {
        // documentId/fileName/metadata 原样保留（同一引用）
        DocumentAstMetadata metadata = DocumentAstMetadata.builder()
                .totalPages(1).documentType(PdfContentType.TEXT_ONLY).build();
        DocumentAst ast = DocumentAst.builder()
                .documentId("doc-uuid-1").fileName("样例.pdf").metadata(metadata)
                .pages(List.of(page(1, List.of(pdfParagraph("正文")))))
                .build();

        DocumentAst cleaned = cleaner.clean(ast);

        assertThat(cleaned.getDocumentId()).isEqualTo("doc-uuid-1");
        assertThat(cleaned.getFileName()).isEqualTo("样例.pdf");
        assertThat(cleaned.getMetadata()).isSameAs(metadata);
    }

    @Test
    void headerFooterReclassified_endToEnd() {
        // 步骤 5 端到端：3 页同顶同文 → HEADER（重分类节点保留在 nodes 原位）
        List<DocumentNode> nodes1 = new ArrayList<>(List.of(
                pdfParagraphAt("第 1 页 共 3 页", topBbox()),
                pdfParagraphAt("正文内容", middleBbox())));
        DocumentAst ast = ast(
                page(1, nodes1),
                page(2, List.of(pdfParagraphAt("第 1 页 共 3 页", topBbox()),
                        pdfParagraphAt("第二页正文", middleBbox()))),
                page(3, List.of(pdfParagraphAt("第 1 页 共 3 页", topBbox()),
                        pdfParagraphAt("第三页正文", middleBbox()))));

        DocumentAst cleaned = cleaner.clean(ast);

        assertThat(cleaned.getPages().get(0).getNodes().get(0).getType())
                .isEqualTo(DocumentNodeType.HEADER);
        assertThat(cleaned.getPages().get(0).getNodes().get(0).getDescription())
                .isEqualTo("第 1 页 共 3 页");
        assertThat(cleaned.getPages().get(2).getNodes().get(1).getType())
                .isEqualTo(DocumentNodeType.PARAGRAPH);
    }

    @Test
    void dedupAndBlankRemoval_endToEnd() {
        // 步骤 6 端到端：同页重复段保首个 + 空白段删除 + KV 完全重复去重
        TableCellNode cell1 = new TableCellNode(0, 0, 1, 1, "键", "同值",
                ElementSource.FUSION, 0.9f, bbox());
        TableCellNode cell2 = new TableCellNode(0, 0, 1, 1, "键", "同值",
                ElementSource.FUSION, 0.9f, BoundingBox.builder().x(1).y(1).width(2).height(2).build());
        DocumentAst ast = ast(page(1, new ArrayList<>(List.of(
                pdfParagraph("重复段落"), pdfParagraph("重复段落"),
                new ParagraphNode("  \n ", ElementSource.PDF_TEXT, 1.0f, bbox(), 10f, "SimSun"),
                table(List.of(cell1)), table(List.of(cell2))))));

        DocumentAst cleaned = cleaner.clean(ast);

        PageNode cleanedPage = cleaned.getPages().get(0);
        assertThat(cleanedPage.getNodes()).hasSize(3);
        assertThat(cleanedPage.getNodes().get(0).getType()).isEqualTo(DocumentNodeType.PARAGRAPH);
        assertThat(cleanedPage.getKeyValues()).hasSize(1);
    }

    // ---------- 夹具 ----------

    private DocumentAst ast(PageNode... pages) {
        return DocumentAst.builder()
                .documentId("doc-uuid").fileName("test.pdf")
                .metadata(DocumentAstMetadata.builder()
                        .totalPages(pages.length).documentType(PdfContentType.TEXT_ONLY).build())
                .pages(List.of(pages))
                .build();
    }

    private PageNode page(int pageNumber, List<DocumentNode> nodes) {
        return PageNode.builder()
                .pageNumber(pageNumber)
                .contentType(PageContentType.TEXT_ONLY)
                .pageWidth(1000f)
                .pageHeight(1000f)
                .nodes(new ArrayList<>(nodes))
                .keyValues(List.of())
                .build();
    }

    private ParagraphNode pdfParagraph(String text) {
        return new ParagraphNode(text, ElementSource.PDF_TEXT, 1.0f, middleBbox(), 10f, "SimSun");
    }

    private ParagraphNode pdfParagraphAt(String text, BoundingBox bbox) {
        return new ParagraphNode(text, ElementSource.PDF_TEXT, 1.0f, bbox, 10f, "SimSun");
    }

    private ParagraphNode ocrParagraph(String text) {
        return new ParagraphNode(text, ElementSource.OCR, 0.8f, middleBbox(), null, null);
    }

    private TableNode table(List<TableCellNode> cells) {
        return new TableNode(cells.size(), cells.size(),
                List.of(new TableRowNode(0, null, cells)), ElementSource.FUSION,
                BoundingBox.builder().x(60).y(300).width(400).height(100).build());
    }

    private String firstParagraphText(DocumentAst ast) {
        return ((ParagraphNode) ast.getPages().get(0).getNodes().get(0)).getText();
    }

    private TableCellNode firstTableCell(DocumentAst ast) {
        TableNode table = (TableNode) ast.getPages().get(0).getNodes().get(0);
        return table.getRows().get(0).getCells().get(0);
    }

    private BoundingBox cellBboxOf(DocumentAst ast, int nodeIndex) {
        TableNode table = (TableNode) ast.getPages().get(0).getNodes().get(nodeIndex);
        return table.getRows().get(0).getCells().get(0).getBbox();
    }

    private BoundingBox topBbox() {
        return BoundingBox.builder().x(100).y(950).width(300).height(30).build();
    }

    private BoundingBox middleBbox() {
        return BoundingBox.builder().x(100).y(400).width(300).height(30).build();
    }

    private BoundingBox bbox() {
        return BoundingBox.builder().x(60).y(300).width(200).height(40).build();
    }
}
