package com.aifp.aiagent.parser.pdf.markdown;

import com.aifp.aiagent.parser.pdf.ast.*;
import com.aifp.aiagent.parser.pdf.page.ElementSource;
import com.aifp.aiagent.parser.pdf.text.BoundingBox;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link MarkdownRenderer} 单元测试（阶段 11）：
 * 纯投影规则逐条固化（标题/段落/表格/键值/页眉页脚跳过/占位透传/页分隔）、
 * GFM 表格细节（首行表头/跨格展开/转义/空行保留）、KV 输出策略开关、
 * 硬约束 A 机械防线（import 纯净性）。
 *
 * @author Tang_tzb
 */
class MarkdownRendererTest {

    private MarkdownRenderer renderer;

    @BeforeEach
    void setUp() {
        renderer = new MarkdownRenderer();
    }

    @Test
    void nullAst_nullOutput() {
        assertThat(renderer.render(null)).isNull();
    }

    @Test
    void emptyAst_emptyString() {
        assertThat(renderer.render(ast(null, List.of()))).isEmpty();
    }

    @Test
    void documentHeader_fileNameRendered() {
        String markdown = renderer.render(ast("样例.pdf", List.of(page(List.of()))));

        assertThat(markdown).isEqualTo("# 样例.pdf\n");
    }

    @Test
    void documentHeader_blankFileName_skipped() {
        assertThat(renderer.render(ast("  ", List.of()))).isEmpty();
        assertThat(renderer.render(ast(null, List.of()))).isEmpty();
    }

    @Test
    void title_renderedAsH2_newlineFlattened() {
        String markdown = renderer.render(ast(null, List.of(
                page(List.of(title("许可\n信息"))))));

        assertThat(markdown).isEqualTo("## 许可 信息\n");
    }

    @Test
    void blankTitle_skipped() {
        assertThat(renderer.render(ast(null, List.of(page(List.of(title("  "))))))).isEmpty();
    }

    @Test
    void paragraph_renderedAsIs_blocksSeparatedByBlankLine() {
        String markdown = renderer.render(ast(null, List.of(page(List.of(
                paragraph("第一段"), paragraph("第二段"))))));

        assertThat(markdown).isEqualTo("第一段\n\n第二段\n");
    }

    @Test
    void headerFooterNodes_skipped() {
        String markdown = renderer.render(ast(null, List.of(page(List.of(
                baseNode(DocumentNodeType.HEADER, "第 1 页"),
                paragraph("正文"),
                baseNode(DocumentNodeType.FOOTER, "共 1 页"))))));

        assertThat(markdown).isEqualTo("正文\n");
    }

    @Test
    void placeholderNodes_descriptionRenderedAsLine() {
        String markdown = renderer.render(ast(null, List.of(page(List.of(
                baseNode(DocumentNodeType.IMAGE, "[IMAGE: 第1页第1张]"),
                baseNode(DocumentNodeType.STAMP, "印章区域"),
                baseNode(DocumentNodeType.SIGNATURE, "  "))))));

        assertThat(markdown).isEqualTo("[IMAGE: 第1页第1张]\n\n印章区域\n");
    }

    @Test
    void table_firstRowHeader_gridFidelity() {
        // 3×2 网格：首行表头、值格显示 value、colSpan=2 合并格覆盖位空、行数保持
        TableNode table = new TableNode(3, 2, List.of(
                new TableRowNode(0, null, List.of(
                        cell(0, 0, "名称", null), cell(0, 1, "数值", null))),
                new TableRowNode(1, null, List.of(
                        cell(1, 0, null, "宽度"), cell(1, 1, null, "17\n0649"))),
                new TableRowNode(2, null, List.of(
                        new TableCellNode(2, 0, 1, 2, null, "合计",
                                ElementSource.PDF_TEXT, 1.0f, bbox())))),
                ElementSource.FUSION, bbox());

        String markdown = renderer.render(ast(null, List.of(page(List.of(table)))));

        assertThat(markdown).isEqualTo("""
                | 名称 | 数值 |
                | --- | --- |
                | 宽度 | 17<br>0649 |
                | 合计 |  |
                """.stripTrailing() + "\n");
    }

    @Test
    void table_missingRow_keptAsEmptyGridRow() {
        // rowCount=3 但只有 2 行数据：第 3 行按空网格行渲染（网格忠实性）
        TableNode table = new TableNode(3, 1, List.of(
                new TableRowNode(0, null, List.of(cell(0, 0, "表头", null))),
                new TableRowNode(1, null, List.of(cell(1, 0, null, "值")))),
                ElementSource.FUSION, bbox());

        String markdown = renderer.render(ast(null, List.of(page(List.of(table)))));

        assertThat(markdown).isEqualTo("""
                | 表头 |
                | --- |
                | 值 |
                |  |
                """.stripTrailing() + "\n");
    }

    @Test
    void table_pipeEscaped() {
        TableNode table = new TableNode(2, 1, List.of(
                new TableRowNode(0, null, List.of(cell(0, 0, "列|头", null))),
                new TableRowNode(1, null, List.of(cell(1, 0, null, "a|b")))),
                ElementSource.PDF_TEXT, bbox());

        String markdown = renderer.render(ast(null, List.of(page(List.of(table)))));

        assertThat(markdown).isEqualTo("""
                | 列\\|头 |
                | --- |
                | a\\|b |
                """.stripTrailing() + "\n");
    }

    @Test
    void table_emptyTable_skippedEntirely() {
        TableNode empty = new TableNode(0, 0, List.of(), ElementSource.FUSION, bbox());

        String markdown = renderer.render(ast(null, List.of(
                page(List.of(empty, paragraph("正文"))))));

        assertThat(markdown).isEqualTo("正文\n");
    }

    @Test
    void keyValues_enabled_markedBlockAfterNodes() {
        PageNode page = pageWithKv(List.of(paragraph("正文")), List.of(
                kv("建设单位", "亳州市教育局"),
                kv("设计单位", "甲\n乙"),
                kv("  ", "无效键")));

        String markdown = renderer.render(ast("样例.pdf", List.of(page)));

        assertThat(markdown).isEqualTo("""
                # 样例.pdf

                正文

                <!-- key-values -->
                建设单位：亳州市教育局
                设计单位：甲 乙
                <!-- /key-values -->
                """.stripTrailing() + "\n");
    }

    @Test
    void keyValues_disabled_noMarkersNoLines() {
        ReflectionTestUtils.setField(renderer, "keyValuesEnabled", false);
        PageNode page = pageWithKv(List.of(paragraph("正文")), List.of(kv("建设单位", "亳州市教育局")));

        String markdown = renderer.render(ast("样例.pdf", List.of(page)));

        assertThat(markdown).doesNotContain("<!-- key-values -->").doesNotContain("建设单位：");
    }

    @Test
    void pages_separatedBySingleHr_noHrBeforeFirstPage() {
        String markdown = renderer.render(ast("样例.pdf", List.of(
                page(List.of(paragraph("第一页"))),
                page(List.of(paragraph("第二页"))),
                page(List.of(paragraph("第三页"))))));

        assertThat(markdown).startsWith("# 样例.pdf\n\n第一页\n");
        assertThat(markdown.split("\n\n---\n\n", -1)).hasSize(3);
        assertThat(markdown).endsWith("第三页\n");
    }

    @Test
    void rendererImports_pureProjection_guard() throws Exception {
        // 硬约束 A 机械防线：渲染器仅允许 ast 包 + JDK + Spring 注解
        Path source = Path.of("src/main/java/com/aifp/aiagent/parser/pdf/markdown/MarkdownRenderer.java");
        org.junit.jupiter.api.Assumptions.assumeTrue(Files.exists(source));

        List<String> imports = Files.readAllLines(source).stream()
                .filter(line -> line.startsWith("import "))
                .map(line -> line.substring("import ".length(), line.indexOf(';')))
                .toList();

        assertThat(imports).isNotEmpty().allSatisfy(pkg -> assertThat(pkg).satisfiesAnyOf(
                p -> assertThat(p).startsWith("java."),
                p -> assertThat(p).startsWith("org.springframework."),
                p -> assertThat(p).startsWith("com.aifp.aiagent.parser.pdf.ast.")));
    }

    // ---------- 夹具 ----------

    private DocumentAst ast(String fileName, List<PageNode> pages) {
        return DocumentAst.builder().documentId("doc-uuid").fileName(fileName)
                .pages(pages).build();
    }

    private PageNode page(List<DocumentNode> nodes) {
        return PageNode.builder().pageNumber(1)
                .contentType(com.aifp.aiagent.parser.pdf.PageContentType.TEXT_ONLY)
                .pageWidth(1000f).pageHeight(1000f)
                .nodes(new java.util.ArrayList<>(nodes))
                .keyValues(List.of())
                .build();
    }

    /**
     * 带键值语义视图的页夹具（KV 渲染开关用例）
     */
    private PageNode pageWithKv(List<DocumentNode> nodes, List<KeyValueNode> keyValues) {
        PageNode page = page(nodes);
        page.setKeyValues(new java.util.ArrayList<>(keyValues));
        return page;
    }

    private TitleNode title(String text) {
        return new TitleNode(text, 16f, "SimHei", bbox());
    }

    private ParagraphNode paragraph(String text) {
        return new ParagraphNode(text, ElementSource.PDF_TEXT, 1.0f, bbox(), 10f, "SimSun");
    }

    private DocumentNode baseNode(DocumentNodeType type, String description) {
        return new DocumentNode(type, ElementSource.PDF_TEXT, 1.0f, bbox(), description);
    }

    private TableCellNode cell(int row, int column, String header, String value) {
        return new TableCellNode(row, column, 1, 1, header, value,
                header != null ? ElementSource.OCR : ElementSource.PDF_TEXT,
                header != null ? 0.9f : 1.0f, bbox());
    }

    private KeyValueNode kv(String key, String value) {
        return new KeyValueNode(key, value, ElementSource.OCR, ElementSource.PDF_TEXT, 0.9f, bbox());
    }

    private BoundingBox bbox() {
        return BoundingBox.builder().x(60).y(300).width(200).height(40).build();
    }
}
