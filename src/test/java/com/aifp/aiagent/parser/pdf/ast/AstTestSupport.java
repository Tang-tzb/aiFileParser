package com.aifp.aiagent.parser.pdf.ast;

import com.aifp.aiagent.parser.pdf.layout.TableCell;
import com.aifp.aiagent.parser.pdf.layout.TableGrid;
import com.aifp.aiagent.parser.pdf.layout.TableRow;
import com.aifp.aiagent.parser.pdf.page.ElementSource;
import com.aifp.aiagent.parser.pdf.page.PageElement;
import com.aifp.aiagent.parser.pdf.page.PageElementType;
import com.aifp.aiagent.parser.pdf.structure.TitleRecognizer;
import com.aifp.aiagent.parser.pdf.text.BoundingBox;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

/**
 * AST 组装层测试夹具：离线构建识别器/组装器与 PageElement/TableGrid 模型
 * （不启动 Spring 容器，@Value 阈值经 ReflectionTestUtils 注入）。
 *
 * @author Tang_tzb
 */
final class AstTestSupport {

    private AstTestSupport() {
    }

    /**
     * 构建注入默认阈值（1.2）的标题识别器。
     */
    static TitleRecognizer buildTitleRecognizer() {
        TitleRecognizer recognizer = new TitleRecognizer();
        ReflectionTestUtils.setField(recognizer, "titleFontRatio", 1.2);
        return recognizer;
    }

    /**
     * 构建页节点组装器（真实依赖链）。
     */
    static PageNodeAssembler buildPageNodeAssembler() {
        return new PageNodeAssembler(new TableNodeAssembler(),
                new com.aifp.aiagent.parser.pdf.structure.KeyValueRecognizer(),
                buildTitleRecognizer());
    }

    /**
     * PDF 原生文字元素。
     */
    static PageElement textElement(String text, float fontSize,
                                   BoundingBox bbox) {
        return PageElement.builder()
                .type(PageElementType.TEXT)
                .source(ElementSource.PDF_TEXT)
                .text(text)
                .bbox(bbox)
                .fontSize(fontSize)
                .fontName("Helvetica")
                .build();
    }

    /**
     * OCR 文字元素（置信度 0~100 口径，无字号/字体）。
     */
    static PageElement ocrElement(String text, float confidencePercent, BoundingBox bbox) {
        return PageElement.builder()
                .type(PageElementType.TEXT)
                .source(ElementSource.OCR)
                .text(text)
                .bbox(bbox)
                .confidence(confidencePercent)
                .build();
    }

    /**
     * 视觉区域占位元素。
     */
    static PageElement regionElement(PageElementType type,
                                     com.aifp.aiagent.parser.pdf.region.RegionType regionType,
                                     String description, BoundingBox bbox) {
        return PageElement.builder()
                .type(type)
                .source(ElementSource.IMAGE)
                .text("")
                .description(description)
                .bbox(bbox)
                .regionType(regionType)
                .build();
    }

    /**
     * 表格单元格。
     */
    static TableCell cell(int rowIndex, int columnIndex, int rowSpan, int colSpan,
                          String header, String value, ElementSource source, Float confidence) {
        return TableCell.builder()
                .rowIndex(rowIndex).columnIndex(columnIndex)
                .rowSpan(rowSpan).colSpan(colSpan)
                .boundingBox(BoundingBox.builder()
                        .x(100f + columnIndex * 100f)
                        .y(300f + rowIndex * 100f)
                        .width(100f).height(100f)
                        .build())
                .header(header).value(value)
                .source(source).confidence(confidence)
                .build();
    }

    /**
     * 表格行视图。
     */
    static TableRow row(int index, List<TableCell> cells) {
        return TableRow.builder()
                .index(index)
                .bbox(BoundingBox.builder().x(100f).y(300f + index * 100f)
                        .width(200f).height(100f).build())
                .cells(cells)
                .build();
    }

    /**
     * 表格格网。
     */
    static TableGrid grid(int rowCount, int columnCount,
                          List<TableRow> rows, List<TableCell> cells) {
        return TableGrid.builder()
                .pageNumber(1)
                .bbox(BoundingBox.builder().x(100f).y(300f).width(200f)
                        .height(100f * rowCount).build())
                .rowCount(rowCount).columnCount(columnCount)
                .rows(rows).cells(cells)
                .build();
    }
}
