package com.aifp.aiagent.parser.pdf.clean;

import com.aifp.aiagent.parser.pdf.ast.KeyValueNode;
import com.aifp.aiagent.parser.pdf.ast.TableCellNode;
import com.aifp.aiagent.parser.pdf.ast.TableNode;
import com.aifp.aiagent.parser.pdf.ast.TableRowNode;
import com.aifp.aiagent.parser.pdf.page.ElementSource;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 表格清洗器（阶段 10，对应《PDF解析改造方案》阶段 10"表格多行"）。
 * <p>
 * 职责（全部以<b>重建新节点</b>表达，硬约束 1）：
 * <ul>
 *   <li>单元格值：{@link LineCleaner#joinCell} 多行归一（无标题守卫，
 *       §十六 建设地点多行必须归一为一个 Cell）；</li>
 *   <li>表头：CJK 边界空白压缩（{@code 建设 单位→建设单位}，Latin 边界保留空格）
 *       → {@link OcrErrorCleaner#fix}（header 为 OCR 源）；</li>
 *   <li>keyValues：从清洗后 cells 重建（复刻 KeyValueRecognizer 的 FUSION 过滤
 *       {@code header!=null && value!=null}），单一事实源，杜绝表格与键值视图漂移
 *       （约束 2 延续）。</li>
 * </ul>
 * 不变量：行/列骨架、bbox 引用透传、source/confidence 原样保留。
 *
 * @author Tang_tzb
 */
@Component
@RequiredArgsConstructor
public class TableCleaner {

    private final CharacterCleaner characterCleaner;
    private final LineCleaner lineCleaner;
    private final OcrErrorCleaner ocrErrorCleaner;

    /**
     * 清洗单个表格（字符 → 值换行归一 → 表头压缩与数字纠错），返回全新 TableNode。
     *
     * @param table 原 TableNode（可 null，不修改）
     * @return 清洗后 TableNode；null 原样返回
     */
    public TableNode cleanTable(TableNode table) {
        if (table == null) {
            return null;
        }
        List<TableRowNode> newRows = new ArrayList<>(table.getRows().size());
        for (TableRowNode row : table.getRows()) {
            newRows.add(cleanRow(row));
        }
        // bbox/source 零改动透传（硬约束 2）
        return new TableNode(table.getRowCount(), table.getColumnCount(), newRows,
                table.getSource(), table.getBbox());
    }

    /**
     * 从清洗后的表格集合重建 keyValues（FUSION 过滤：header!=null && value!=null），
     * 行主序遍历 = 阅读序。
     *
     * @param cleanedTables 清洗后的表格列表（不可 null 元素）
     * @return 键值节点列表（无命中为空列表）
     */
    public List<KeyValueNode> rebuildKeyValues(List<TableNode> cleanedTables) {
        List<KeyValueNode> result = new ArrayList<>();
        if (cleanedTables == null) {
            return List.of();
        }
        for (TableNode table : cleanedTables) {
            for (TableRowNode row : table.getRows()) {
                for (TableCellNode cell : row.getCells()) {
                    if (cell.getHeader() != null && cell.getValue() != null) {
                        // key=OCR 表头 / value=PDF 原生值，与 KeyValueRecognizer 口径一致
                        result.add(new KeyValueNode(cell.getHeader(), cell.getValue(),
                                ElementSource.OCR, ElementSource.PDF_TEXT,
                                cell.getConfidence(), cell.getBbox()));
                    }
                }
            }
        }
        return List.copyOf(result);
    }

    private TableRowNode cleanRow(TableRowNode row) {
        List<TableCellNode> newCells = new ArrayList<>(row.getCells().size());
        for (TableCellNode cell : row.getCells()) {
            newCells.add(cleanCell(cell));
        }
        return new TableRowNode(row.getIndex(), row.getBbox(), newCells);
    }

    private TableCellNode cleanCell(TableCellNode cell) {
        // 值：PDF_TEXT 零损耗——安全空白规范（字符级）+ 换行归一，不进 OCR 纠错
        String value = cell.getValue() == null ? null
                : lineCleaner.joinCell(characterCleaner.clean(cell.getValue()));
        return new TableCellNode(cell.getRowIndex(), cell.getColumnIndex(),
                cell.getRowSpan(), cell.getColSpan(),
                cleanHeader(cell.getHeader()), value,
                cell.getSource(), cell.getConfidence(), cell.getBbox());
    }

    /**
     * 表头清洗：字符级归一 → CJK 边界空白压缩 → OCR 数字纠错（header 为 OCR 源）。
     */
    private String cleanHeader(String header) {
        if (header == null || header.isEmpty()) {
            return header;
        }
        return ocrErrorCleaner.fix(compressHeaderWhitespace(characterCleaner.clean(header)));
    }

    /**
     * CJK 边界空白压缩：CJK-CJK 边界空白删除，其余空白折叠为单空格，行首尾 trim。
     * <p>
     * 输入已经 CharacterCleaner 归一（空白仅为普通空格），此处仍按通用空白字符
     * 处理以保证独立可用性。
     */
    private String compressHeaderWhitespace(String header) {
        StringBuilder sb = new StringBuilder(header.length());
        int i = 0;
        while (i < header.length()) {
            char c = header.charAt(i);
            if (!Character.isWhitespace(c)) {
                sb.append(c);
                i++;
                continue;
            }
            // 空白串：向前找已保留末字符、向后找下一非空白字符
            int j = i;
            while (j < header.length() && Character.isWhitespace(header.charAt(j))) {
                j++;
            }
            boolean prevCjk = sb.length() > 0 && isCjk(sb.charAt(sb.length() - 1));
            boolean nextCjk = j < header.length() && isCjk(header.charAt(j));
            if (!(prevCjk && nextCjk) && sb.length() > 0) {
                sb.append(' ');
            }
            i = j;
        }
        return sb.toString().trim();
    }

    /**
     * CJK 判定（与 LineCleaner 同口径：表意文字 + CJK 标点 + 全角形式）。
     */
    private boolean isCjk(char c) {
        return (c >= 0x3400 && c <= 0x4DBF)
                || (c >= 0x4E00 && c <= 0x9FFF)
                || (c >= 0xF900 && c <= 0xFAFF)
                || (c >= 0x3000 && c <= 0x303F)
                || (c >= 0xFF00 && c <= 0xFFEF);
    }
}
