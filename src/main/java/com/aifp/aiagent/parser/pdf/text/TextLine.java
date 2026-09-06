package com.aifp.aiagent.parser.pdf.text;

import lombok.Builder;
import lombok.Data;

/**
 * 文字行：同页文字按 Y 坐标聚合的一行，行内文字按 X 坐标排序。
 * <p>
 * 行为文字结构化的中间单元，多行按垂直间距聚合为 {@link TextBlock}。
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
}
