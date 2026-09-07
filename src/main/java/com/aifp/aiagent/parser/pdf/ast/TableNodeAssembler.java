package com.aifp.aiagent.parser.pdf.ast;

import com.aifp.aiagent.parser.pdf.layout.TableCell;
import com.aifp.aiagent.parser.pdf.layout.TableGrid;
import com.aifp.aiagent.parser.pdf.layout.TableRow;
import com.aifp.aiagent.parser.pdf.page.ElementSource;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 表格节点组装器（阶段 9）：{@link TableGrid} → {@link TableNode}。
 * <p>
 * 行/列/span/header/value/source/confidence/bbox 全量透传，
 * 不做任何清洗与重排（清洗为阶段 10 职责，保持 AST 忠实于解析产物）。
 * 表级 source 由单元格聚合推导：任一 FUSION → FUSION，否则任一 OCR → OCR，
 * 否则 PDF_TEXT。
 *
 * @author Tang_tzb
 */
@Component
public class TableNodeAssembler {

    /**
     * 组装表格节点。
     *
     * @param grid 表格格网（阶段 7 产物，可为 null → 返回 null 由调用方过滤）
     * @return 表格节点
     */
    public TableNode assemble(TableGrid grid) {
        if (grid == null) {
            return null;
        }
        List<TableRowNode> rows = new ArrayList<>();
        if (grid.getRows() != null) {
            for (TableRow row : grid.getRows()) {
                rows.add(new TableRowNode(row.getIndex(), row.getBbox(),
                        toCellNodes(row.getCells())));
            }
        }
        return new TableNode(grid.getRowCount(), grid.getColumnCount(), rows,
                deriveSource(grid.getCells()), grid.getBbox());
    }

    /**
     * 单元格 → 节点（字段全量透传）。
     */
    private List<TableCellNode> toCellNodes(List<TableCell> cells) {
        List<TableCellNode> nodes = new ArrayList<>();
        if (cells == null) {
            return nodes;
        }
        for (TableCell cell : cells) {
            nodes.add(new TableCellNode(
                    cell.getRowIndex(), cell.getColumnIndex(),
                    cell.getRowSpan(), cell.getColSpan(),
                    cell.getHeader(), cell.getValue(),
                    cell.getSource(), cell.getConfidence(), cell.getBoundingBox()));
        }
        return nodes;
    }

    /**
     * 表级来源聚合：FUSION &gt; OCR &gt; PDF_TEXT（见类注释推导规则）。
     */
    private ElementSource deriveSource(List<TableCell> cells) {
        if (cells == null || cells.isEmpty()) {
            return ElementSource.PDF_TEXT;
        }
        boolean hasOcr = false;
        for (TableCell cell : cells) {
            ElementSource source = cell.getSource();
            if (source == ElementSource.FUSION) {
                return ElementSource.FUSION;
            }
            hasOcr = hasOcr || source == ElementSource.OCR;
        }
        return hasOcr ? ElementSource.OCR : ElementSource.PDF_TEXT;
    }
}
