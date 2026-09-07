package com.aifp.aiagent.parser.pdf.layout;

import com.aifp.aiagent.parser.pdf.text.BoundingBox;
import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * 表格格网：表格结构恢复的统一输出模型（阶段 7，对应《PDF解析改造方案》§八）。
 * <p>
 * 方式一（图像线检测）与方式二（文字坐标聚类）产出同一模型；
 * 值绑定/表头 OCR/表头-值融合由 HybridTableRecognizer 在其上完成。
 * <p>
 * 尺寸语义：rowCount/columnCount 描述原始格网骨架（含被过滤的空单元格），
 * cells 为有效单元格子集（过滤后），二者配合表达"骨架尺寸 + 实际内容"。
 *
 * @author Tang_tzb
 */
@Data
@Builder
public class TableGrid {

    /**
     * 页码（1-based）
     */
    private int pageNumber;

    /**
     * 表格外接矩形（全部单元格并集，PDF 用户空间）
     */
    private BoundingBox bbox;

    /**
     * 行数（格网骨架）
     */
    private int rowCount;

    /**
     * 列数（格网骨架）
     */
    private int columnCount;

    /**
     * 行视图（index 升序 = 自页顶向下）
     */
    private List<TableRow> rows;

    /**
     * 全部有效单元格（含跨行/跨列单元格）
     */
    private List<TableCell> cells;
}
