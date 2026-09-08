package com.aifp.aiagent.parser.pdf.chunk;

import com.aifp.aiagent.parser.pdf.ast.TableCellNode;
import com.aifp.aiagent.parser.pdf.ast.TableNode;
import com.aifp.aiagent.parser.pdf.ast.TableRowNode;
import org.springframework.stereotype.Component;

/**
 * 表格 GFM 文本渲染器（阶段 12）：将 {@link TableNode} 投影为 Markdown 表格文本，
 * 算法与阶段 11 MarkdownRenderer 表格渲染保持一致（首行表头 + {@code | --- |}
 * 分隔行 + rowSpan/colSpan 覆盖位空串 + rowCount 网格忠实不删空行 +
 * {@code |}→{@code \|}、换行→{@code <br>} 转义）。
 * <p>
 * 独立实现而非复用 MarkdownRenderer：切片需要<b>行级渲染/行子集</b>能力
 * （大表格"表头 + N 行"行组块每组重复表头，SemanticChunker 贪心分组需逐行
 * 估算 token），MarkdownRenderer 仅支持整表且表格方法为私有，跨包耦合不值
 * （阶段 12 计划决策 D4）。
 * <p>
 * 空表（rows 为空或列数 ≤ 0）返回 {@code null}，调用方不成块
 * （与 MarkdownRenderer 空表跳过口径一致）。
 *
 * @author Tang_tzb
 */
@Component
public class TableTextRenderer {

    /**
     * 整表渲染：表头行 + 分隔行 + 全部数据行。
     *
     * @param table 表格节点
     * @return GFM 文本；空表返回 null
     */
    public String render(TableNode table) {
        return renderRowGroup(table, 1, table == null ? 0 : table.getRowCount());
    }

    /**
     * 行组渲染：表头行（网格首行）+ 分隔行 + 数据行
     * {@code [fromDataRow, toDataRowExclusive)}——大表格行组块<b>每组重复表头</b>。
     *
     * @param table              表格节点
     * @param fromDataRow        起始数据行索引（1-based，小于 1 视为 1）
     * @param toDataRowExclusive 结束数据行索引（排他，自动裁剪至 rowCount）
     * @return GFM 文本；空表或无有效数据行返回 null
     */
    public String renderRowGroup(TableNode table, int fromDataRow, int toDataRowExclusive) {
        if (isEmpty(table)) {
            return null;
        }
        int from = Math.max(1, fromDataRow);
        int to = Math.min(table.getRowCount(), toDataRowExclusive);
        if (from >= to) {
            return null;
        }
        String[][] grid = buildGrid(table);
        StringBuilder sb = new StringBuilder();
        sb.append(renderRow(grid[0])).append('\n').append(renderDelimiter(table.getColumnCount()));
        for (int r = from; r < to; r++) {
            sb.append('\n').append(renderRow(grid[r]));
        }
        return sb.toString();
    }

    /**
     * 网格构建（包私有，供 SemanticChunker 逐行估算 token 复用）：
     * 默认空串，单元格显示文本落位于起始坐标（覆盖位不写，保持网格忠实）；
     * 空表返回 null。
     */
    String[][] buildGrid(TableNode table) {
        if (isEmpty(table)) {
            return null;
        }
        String[][] grid = new String[table.getRowCount()][table.getColumnCount()];
        for (String[] row : grid) {
            java.util.Arrays.fill(row, "");
        }
        for (TableRowNode row : table.getRows()) {
            if (row == null || row.getCells() == null) {
                continue;
            }
            for (TableCellNode cell : row.getCells()) {
                if (cell == null || cell.getRowIndex() < 0 || cell.getRowIndex() >= grid.length
                        || cell.getColumnIndex() < 0 || cell.getColumnIndex() >= grid[0].length) {
                    continue;
                }
                grid[cell.getRowIndex()][cell.getColumnIndex()] = cellText(cell);
            }
        }
        return grid;
    }

    /**
     * 单行渲染 {@code | a | b |}（包私有，供逐行 token 估算复用）。
     */
    String renderRow(String[] cells) {
        StringBuilder sb = new StringBuilder();
        sb.append('|');
        for (String cell : cells) {
            sb.append(' ').append(cell).append(" |");
        }
        return sb.toString();
    }

    /**
     * GFM 表头分隔行 {@code | --- | --- |}（包私有）。
     */
    String renderDelimiter(int columnCount) {
        StringBuilder sb = new StringBuilder();
        sb.append('|');
        for (int c = 0; c < columnCount; c++) {
            sb.append(" --- |");
        }
        return sb.toString();
    }

    /**
     * 空表判定：null / 行列数 ≤ 0 / rows 为空。
     */
    private boolean isEmpty(TableNode table) {
        return table == null || table.getRowCount() <= 0 || table.getColumnCount() <= 0
                || table.getRows() == null || table.getRows().isEmpty();
    }

    /**
     * 单元格显示文本：值格显示 value、纯表头格显示 header、空格显示 ""（阶段 9 口径）；
     * 转义 {@code |} → {@code \|}、换行 → {@code <br>}（GFM 单元格换行标准写法）。
     */
    private String cellText(TableCellNode cell) {
        String text = cell.getValue() != null ? cell.getValue() : cell.getHeader();
        if (text == null) {
            return "";
        }
        return text.replace("|", "\\|")
                .replace("\r\n", "\n")
                .replace("\n", "<br>");
    }
}
