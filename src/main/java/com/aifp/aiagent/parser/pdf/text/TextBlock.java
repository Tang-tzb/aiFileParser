package com.aifp.aiagent.parser.pdf.text;

import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * 文字块：多行文字按垂直间距聚合的结构化单元（阶段 3 核心产物）。
 * <p>
 * 保留字段清单（阶段验收要求）：text、x/y/width/height（经 {@link #bbox}）、
 * fontSize、fontName、page；行级明细见 {@link #lines}。
 *
 * @author Tang_tzb
 */
@Data
@Builder
public class TextBlock {

    /**
     * 块文字（行文字按阅读顺序以 \n 连接）
     */
    private String text;

    /**
     * 行列表（自上而下排列）
     */
    private List<TextLine> lines;

    /**
     * 块外接矩形（全部行的并集，PDF 用户空间，含 x/y/width/height）
     */
    private BoundingBox bbox;

    /**
     * 主字号（pt，块内按字符数占比取最大）
     */
    private float fontSize;

    /**
     * 主字体名
     */
    private String fontName;

    /**
     * 页码（1-based）
     */
    private int page;
}
