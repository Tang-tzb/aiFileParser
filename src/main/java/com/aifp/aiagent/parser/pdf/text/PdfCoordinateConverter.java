package com.aifp.aiagent.parser.pdf.text;

import org.apache.pdfbox.text.TextPosition;
import org.springframework.stereotype.Component;

/**
 * PDF 坐标转换器：统一 PDFTextStripper 的 dir 坐标（top-down，原点左上，y 向下）
 * 与 PDF 用户空间（bottom-left，原点左下，y 向上）。
 * <p>
 * 阶段 3 仅需单向换算（dir → 用户空间）；阶段 4 OCR 像素坐标、阶段 8 多源融合
 * 将在此基础上扩展图片坐标系换算，方法签名保持稳定。
 * <p>
 * 已知局限：dir 坐标对旋转页（rotation≠0）为近似值，精确旋转处理由阶段 8 统一解决。
 *
 * @author Tang_tzb
 */
@Component
public class PdfCoordinateConverter {

    /**
     * 单字形 box（top-down）→ PDF 用户空间：y_user = pageHeight − y_topdown − height。
     */
    public BoundingBox toUserSpace(BoundingBox topDown, float pageHeight) {
        float yUser = pageHeight - topDown.getY() - topDown.getHeight();
        return BoundingBox.builder()
                .x(topDown.getX())
                .y(yUser)
                .width(topDown.getWidth())
                .height(topDown.getHeight())
                .build();
    }

    /**
     * 由 {@link TextPosition} 构建用户空间字形 box：
     * x=getXDirAdj，字形顶=yDirAdj−heightDir（dir 基线近似字形底），随后整体转用户空间。
     */
    public BoundingBox toUserSpace(TextPosition pos, float pageHeight) {
        BoundingBox topDown = BoundingBox.builder()
                .x(pos.getXDirAdj())
                .y(pos.getYDirAdj() - pos.getHeightDir())
                .width(pos.getWidthDirAdj())
                .height(pos.getHeightDir())
                .build();
        return toUserSpace(topDown, pageHeight);
    }
}
