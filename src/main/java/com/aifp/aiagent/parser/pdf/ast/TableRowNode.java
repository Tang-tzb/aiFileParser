package com.aifp.aiagent.parser.pdf.ast;

import com.aifp.aiagent.parser.pdf.text.BoundingBox;
import lombok.Getter;

import java.util.List;

/**
 * 表格行节点（阶段 9）：TableNode 的行级视图。
 * <p>
 * 结构性节点：source/confidence 为 null（数据口径由 {@link TableCellNode} 承载）。
 * cells 仅承载本行起始（rowIndex == index）的单元格，跨行单元格不出现在被跨行，
 * 与 TableGrid/TableRow 语义一致。
 *
 * @author Tang_tzb
 */
@Getter
public class TableRowNode extends DocumentNode {

    /**
     * 行号（自页顶向下，0-based）
     */
    private final int index;

    /**
     * 本行起始的单元格节点
     */
    private final List<TableCellNode> cells;

    public TableRowNode(int index, BoundingBox bbox, List<TableCellNode> cells) {
        super(DocumentNodeType.TABLE_ROW, null, null, bbox, null);
        this.index = index;
        this.cells = cells;
    }
}
