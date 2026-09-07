package com.aifp.aiagent.parser.pdf.structure;

import com.aifp.aiagent.parser.pdf.ast.KeyValueNode;
import com.aifp.aiagent.parser.pdf.layout.TableCell;
import com.aifp.aiagent.parser.pdf.layout.TableGrid;
import com.aifp.aiagent.parser.pdf.page.ElementSource;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 键值识别器（阶段 9，对应《PDF解析改造方案》§二十六 structure 包）。
 * <p>
 * 来源范围（用户确认，2026-09-07）：<b>仅表格 FUSION 格</b>——阶段 8 表头-值
 * 绑定（HybridTableRecognizer）已保证 header/value 空间对齐，零误判成本；
 * TEXT_ONLY "键：值" 文本模式启发式不做（避免误判，留待按需扩展）。
 * <p>
 * 产物为语义视图：进入 {@code PageNode.keyValues}（不进入阅读序 nodes，
 * 约束 2 见 PageNode 类注释）。
 *
 * @author Tang_tzb
 */
@Component
public class KeyValueRecognizer {

    /**
     * 从表格格网提取键值节点。
     * <p>
     * 判定条件：header != null && value != null（即表头-值绑定成功的 FUSION 格，
     * 与 TableCell.source=FUSION 契约一致）。
     *
     * @param grid 表格格网（可 null）
     * @return 键值节点列表（保持格网行-列结构序，天然确定）
     */
    public List<KeyValueNode> extract(TableGrid grid) {
        if (grid == null || grid.getCells() == null) {
            return List.of();
        }
        List<KeyValueNode> result = new ArrayList<>();
        for (TableCell cell : grid.getCells()) {
            if (cell.getHeader() != null && cell.getValue() != null) {
                result.add(new KeyValueNode(
                        cell.getHeader(), cell.getValue(),
                        ElementSource.OCR, ElementSource.PDF_TEXT,
                        cell.getConfidence(), cell.getBoundingBox()));
            }
        }
        return List.copyOf(result);
    }
}
