package com.aifp.aiagent.parser.pdf.layout;

import com.aifp.aiagent.parser.pdf.region.VisualRegion;
import com.aifp.aiagent.parser.pdf.text.TextBlock;
import lombok.Builder;
import lombok.Data;

import java.awt.image.BufferedImage;
import java.util.List;

/**
 * 表格结构识别输入：一次表格识别的全部上下文（阶段 7）。
 * <p>
 * 渲染图由 MixedPageParser 复用（单页仅渲染一次的约束不变）；
 * 表格线识别在<b>全页渲染图</b>上进行（标签列位于文字候选 bbox 之外，
 * 不能按区域裁剪识别）；regions 仅作印章/签名排除等辅助判定输入。
 *
 * @author Tang_tzb
 */
@Data
@Builder
public class TableRecognitionInput {

    /**
     * 页码（1-based）
     */
    private int pageNumber;

    /**
     * 当页渲染图（单页一次渲染的复用本体；识别层不负责其生命周期）
     */
    private BufferedImage renderedPage;

    /**
     * 渲染 DPI（像素 ↔ PDF 用户空间换算基准）
     */
    private float dpi;

    /**
     * 页面宽度（pt，MediaBox）
     */
    private float pageWidth;

    /**
     * 页面高度（pt，MediaBox）
     */
    private float pageHeight;

    /**
     * PDF 原生文字块（值绑定唯一来源）
     */
    private List<TextBlock> textBlocks;

    /**
     * 视觉区域（印章/签名排除判定输入；不参与表格线识别空间范围）
     */
    private List<VisualRegion> regions;
}
