package com.aifp.aiagent.parser.pdf.text;

import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * 文字行：同页文字按 Y 坐标聚合的一行，行内文字按 X 坐标排序。
 * <p>
 * 行为文字结构化的中间单元，多行按垂直间距聚合为 {@link TextBlock}。
 * 行内按大几何间隙（&gt; segment-gap-ratio × 字号）切分为 {@link Segment}：
 * 制式表单同一视觉行常横跨多个表格单元格（如"单位名称 [大间隙] 法人代表"），
 * 段是表格值绑定的原子单位，防止相邻字段经整行绑定串列。
 *
 * @author Tang_tzb
 */
@Data
@Builder
public class TextLine {

    /**
     * 行内文字（已按 X 排序，几何间隙超过阈值处插入空格）
     */
    private String text;

    /**
     * 行外接矩形（行内全部字形的并集，PDF 用户空间）
     */
    private BoundingBox bbox;

    /**
     * 主字号（pt，按字符数占比取最大）
     */
    private float fontSize;

    /**
     * 主字体名
     */
    private String fontName;

    /**
     * 行内分段（按大几何间隙切分，按 X 排序；单段行也含一个元素）。
     * 阶段 3 引入，阶段 7 表格值绑定消费；旧构造点（未赋值）为 null，
     * 消费方需按"整行 = 单段"兜底。
     */
    private List<Segment> segments;

    /**
     * 行内段：段文字（含段内小间隙空格）+ 段外接矩形（PDF 用户空间）。
     */
    public record Segment(String text, BoundingBox bbox) {
    }
}
