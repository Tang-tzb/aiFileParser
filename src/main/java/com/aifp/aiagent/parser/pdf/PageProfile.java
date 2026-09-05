package com.aifp.aiagent.parser.pdf;

import lombok.Builder;
import lombok.Data;

/**
 * 页面画像：单页内容检测的结构化结果
 * <p>
 * 字段覆盖《PDF解析改造方案》第四节的 PageProfile 清单，供 PageParserRouter（阶段 2）
 * 按页选择解析策略使用。坐标单位为 PDF point（1/72 英寸）。
 *
 * @author Tang_tzb
 */
@Data
@Builder
public class PageProfile {

    /**
     * 页码（1-based，对齐 PDF 惯例）
     */
    private int pageNumber;

    /**
     * 页面宽度（MediaBox，单位 pt）
     */
    private float pageWidth;

    /**
     * 页面高度（MediaBox，单位 pt）
     */
    private float pageHeight;

    /**
     * 非空白文字字符数（来自 TextPosition 统计）
     */
    private int textCount;

    /**
     * 图片 XObject 数量
     */
    private int imageCount;

    /**
     * 文字覆盖面积占比：Σ(TextPosition 宽×高) / 页面面积（近似值，仅记录不参与分类）
     */
    private double textAreaRatio;

    /**
     * 图片占位面积占比：Σ(图片实际占位面积) / 页面面积
     */
    private double imageAreaRatio;

    /**
     * 是否存在整页图片（单图占位比 >= full-image-ratio）
     */
    private boolean hasFullPageImage;

    /**
     * 是否存在大图（单图占位比 >= large-image-ratio，Logo/印章小图不算）
     */
    private boolean hasLargeImage;

    /**
     * 是否存在表格状区域。
     * 本阶段恒为 false，由阶段 7 表格结构识别填充。
     */
    private boolean hasTableLikeRegion;

    /**
     * 页面内容类型
     */
    private PageContentType contentType;
}
