package com.aifp.aiagent.parser.pdf.layout;

import com.aifp.aiagent.parser.pdf.text.BoundingBox;
import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * 表格行：格网中一行的视图模型（阶段 7）。
 * <p>
 * cells 仅承载<b>本行起始</b>（rowIndex == 行号）的单元格；
 * 跨行单元格（rowSpan &gt; 1）不出现在被跨行的 cells 中，
 * 全量单元格见 {@link TableGrid#getCells()}。
 *
 * @author Tang_tzb
 */
@Data
@Builder
public class TableRow {

    /**
     * 行号（自页顶向下，0-based，与 TableCell.rowIndex 对齐）
     */
    private int index;

    /**
     * 行外接矩形（PDF 用户空间）
     */
    private BoundingBox bbox;

    /**
     * 本行起始的单元格（rowIndex == index）
     */
    private List<TableCell> cells;
}
