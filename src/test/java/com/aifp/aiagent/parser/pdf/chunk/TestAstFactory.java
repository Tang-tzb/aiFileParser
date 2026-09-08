package com.aifp.aiagent.parser.pdf.chunk;

import com.aifp.aiagent.parser.pdf.ast.*;
import com.aifp.aiagent.parser.pdf.page.ElementSource;
import com.aifp.aiagent.parser.pdf.text.BoundingBox;

import java.util.ArrayList;
import java.util.List;

/**
 * 阶段 12 切片测试 AST 构建工厂（仅测试用，离线无 Spring 容器）。
 *
 * @author Tang_tzb
 */
final class TestAstFactory {

    private TestAstFactory() {
    }

    /**
     * 构建 DocumentAst。
     */
    static DocumentAst ast(String fileName, PageNode... pages) {
        return DocumentAst.builder()
                .documentId("doc-1")
                .fileName(fileName)
                .metadata(DocumentAstMetadata.builder().totalPages(pages.length).build())
                .pages(List.of(pages))
                .build();
    }

    /**
     * 构建单页（TEXT_ONLY，keyValues 可 null → 空列表）。
     */
    static PageNode page(int pageNumber, List<DocumentNode> nodes, List<KeyValueNode> keyValues) {
        return PageNode.builder()
                .pageNumber(pageNumber)
                .contentType(com.aifp.aiagent.parser.pdf.PageContentType.TEXT_ONLY)
                .pageWidth(595f)
                .pageHeight(842f)
                .nodes(nodes)
                .keyValues(keyValues == null ? List.of() : keyValues)
                .build();
    }

    /**
     * PDF 原生文字段落（confidence=1.0，bbox 固定 y=700 带）。
     */
    static ParagraphNode paragraph(String text) {
        return new ParagraphNode(text, ElementSource.PDF_TEXT, 1.0f,
                BoundingBox.builder().x(50f).y(700f).width(400f).height(20f).build(), 12f, "F1");
    }

    /**
     * OCR 段落（confidence=0.6，bbox 固定 y=600 带，与 paragraph 的 bbox 可并集）。
     */
    static ParagraphNode ocrParagraph(String text) {
        return new ParagraphNode(text, ElementSource.OCR, 0.6f,
                BoundingBox.builder().x(50f).y(600f).width(400f).height(20f).build(), null, null);
    }

    /**
     * 标题节点。
     */
    static TitleNode title(String text, float fontSize) {
        return new TitleNode(text, fontSize, "Bold",
                BoundingBox.builder().x(50f).y(780f).width(200f).height(18f).build());
    }

    /**
     * 规则表格：首行表头值格"表头{c}"，数据行"数据{r}_{c}"；单元格 bbox=null。
     */
    static TableNode table(int rowCount, int columnCount) {
        List<TableRowNode> rows = new ArrayList<>(rowCount);
        for (int r = 0; r < rowCount; r++) {
            List<TableCellNode> cells = new ArrayList<>(columnCount);
            for (int c = 0; c < columnCount; c++) {
                String value = r == 0 ? "表头" + c : "数据" + r + "_" + c;
                cells.add(new TableCellNode(r, c, 1, 1, null, value,
                        ElementSource.PDF_TEXT, 1.0f, null));
            }
            rows.add(new TableRowNode(r, null, cells));
        }
        return new TableNode(rowCount, columnCount, rows, ElementSource.PDF_TEXT,
                BoundingBox.builder().x(50f).y(300f).width(495f).height(300f).build());
    }

    /**
     * 键值节点（key 源 OCR / value 源 PDF_TEXT，confidence=0.7）。
     */
    static KeyValueNode kv(String key, String value) {
        return new KeyValueNode(key, value, ElementSource.OCR, ElementSource.PDF_TEXT, 0.7f,
                BoundingBox.builder().x(50f).y(100f).width(100f).height(14f).build());
    }
}
