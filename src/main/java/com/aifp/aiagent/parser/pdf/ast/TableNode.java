package com.aifp.aiagent.parser.pdf.ast;

import com.aifp.aiagent.parser.pdf.page.ElementSource;
import com.aifp.aiagent.parser.pdf.text.BoundingBox;
import lombok.Getter;

import java.util.List;

/**
 * 表格节点（阶段 9）：阶段 7 TableGrid 结构恢复产物的 AST 视图。
 * <p>
 * rows 为行视图（index 升序 = 自页顶向下），单元格经 TableRowNode/TableCellNode
 * 逐级嵌套；rowCount/columnCount 描述格网骨架尺寸（含被过滤空单元格），
 * 与 TableGrid 语义一致。
 *
 * @author Tang_tzb
 */
@Getter
public class TableNode extends DocumentNode {

    /**
     * 行数（格网骨架）
     */
    private final int rowCount;

    /**
     * 列数（格网骨架）
     */
    private final int columnCount;

    /**
     * 行节点列表（自页顶向下）
     */
    private final List<TableRowNode> rows;

    public TableNode(int rowCount, int columnCount, List<TableRowNode> rows,
                     ElementSource source, BoundingBox bbox) {
        super(DocumentNodeType.TABLE, source, null, bbox, null);
        this.rowCount = rowCount;
        this.columnCount = columnCount;
        this.rows = rows;
    }
}
