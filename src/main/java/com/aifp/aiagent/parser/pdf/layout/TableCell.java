package com.aifp.aiagent.parser.pdf.layout;

import com.aifp.aiagent.parser.pdf.page.ElementSource;
import com.aifp.aiagent.parser.pdf.text.BoundingBox;
import lombok.Builder;
import lombok.Data;

/**
 * 表格单元格：表格结构恢复的最小语义单元（阶段 7，对应《PDF解析改造方案》§八/§十四）。
 * <p>
 * 行列索引约定：<b>rowIndex 自页顶向下、columnIndex 自左向右，均从 0 起</b>；
 * bbox 恒为 PDF 用户空间（原点左下，与 BoundingBox 全局约定一致）。
 * <p>
 * 字段来源约定（source 单值承载"该单元格最终数据口径"）：
 * <ul>
 *   <li>值单元格（有 value）：source=PDF_TEXT、confidence=1.0（原生文字零损耗）；</li>
 *   <li>纯表头单元格（仅 OCR header）：source=OCR、confidence=表头识别置信度；</li>
 *   <li>表头-值绑定后的值单元格：source=FUSION、confidence=0.5+0.5×表头置信度。</li>
 * </ul>
 *
 * @author Tang_tzb
 */
@Data
@Builder
public class TableCell {

    /**
     * 行索引（自页顶向下，0-based）
     */
    private int rowIndex;

    /**
     * 列索引（自左向右，0-based）
     */
    private int columnIndex;

    /**
     * 跨行数（≥1；合并单元格 &gt;1）
     */
    private int rowSpan;

    /**
     * 跨列数（≥1；合并单元格 &gt;1）
     */
    private int colSpan;

    /**
     * 单元格外接矩形（PDF 用户空间；有效单元格必非空且宽高 &gt; 0）
     */
    private BoundingBox boundingBox;

    /**
     * 表头文字（来自 Header Region OCR；OCR 失败/缺失优雅降级为 null）
     */
    private String header;

    /**
     * 单元格值（PDF 原生文字，多行按阅读序以 \n 连接；无值为 null）
     */
    private String value;

    /**
     * 数据来源（PDF_TEXT / OCR / FUSION，见类注释约定）
     */
    private ElementSource source;

    /**
     * 置信度（0~1，语义见类注释；骨架单元格为 null）
     */
    private Float confidence;
}
