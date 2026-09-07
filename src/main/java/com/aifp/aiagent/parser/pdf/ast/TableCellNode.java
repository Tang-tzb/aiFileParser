package com.aifp.aiagent.parser.pdf.ast;

import com.aifp.aiagent.parser.pdf.page.ElementSource;
import com.aifp.aiagent.parser.pdf.text.BoundingBox;
import lombok.Getter;

/**
 * 表格单元格节点（阶段 9）：表格最小语义单元，承载表头-值绑定结果。
 * <p>
 * header/value/source/confidence 语义与阶段 8 TableCell 一致：
 * 值格 source=PDF_TEXT/confidence=1.0；纯表头格 source=OCR；
 * 表头-值绑定后的值格 source=FUSION、confidence=0.5+0.5×表头置信度。
 *
 * @author Tang_tzb
 */
@Getter
public class TableCellNode extends DocumentNode {

    /**
     * 行索引（自页顶向下，0-based）
     */
    private final int rowIndex;

    /**
     * 列索引（自左向右，0-based）
     */
    private final int columnIndex;

    /**
     * 跨行数（≥1）
     */
    private final int rowSpan;

    /**
     * 跨列数（≥1）
     */
    private final int colSpan;

    /**
     * 表头文字（OCR；缺失优雅降级为 null）
     */
    private final String header;

    /**
     * 单元格值（PDF 原生文字，多行按阅读序以 \n 连接；无值为 null）
     */
    private final String value;

    public TableCellNode(int rowIndex, int columnIndex, int rowSpan, int colSpan,
                         String header, String value, ElementSource source,
                         Float confidence, BoundingBox bbox) {
        super(DocumentNodeType.TABLE_CELL, source, confidence, bbox, null);
        this.rowIndex = rowIndex;
        this.columnIndex = columnIndex;
        this.rowSpan = rowSpan;
        this.colSpan = colSpan;
        this.header = header;
        this.value = value;
    }
}
