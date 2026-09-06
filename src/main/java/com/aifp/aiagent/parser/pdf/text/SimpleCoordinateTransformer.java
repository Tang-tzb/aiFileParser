package com.aifp.aiagent.parser.pdf.text;

import org.springframework.stereotype.Component;

/**
 * 坐标转换简单实现（阶段 4 引入，阶段 5 扩展）：等比 DPI 换算 + Y 轴翻转。
 * <p>
 * 换算规则（imageToPdf/pdfToImage/normalize 统一采用，
 * 页面几何基准 = MediaBox + 原点(0,0) + rotation=0）：
 * {@code scale = 72 / dpi}；
 * imageToPdf：{@code x' = x·scale}，{@code w' = w·scale}，
 * {@code y' = pageHeight − (y + h)·scale}，{@code h' = h·scale}；
 * pdfToImage 为其精确逆变换（k = dpi/72）。
 * 已知局限：rotation≠0 页面换算不成立、CropBox 偏移页不处理，
 * 由 {@code SpatialConsistencyTest} 固化限制，留待后续融合阶段。
 *
 * @author Tang_tzb
 */
@Component
public class SimpleCoordinateTransformer implements CoordinateTransformer {

    /**
     * PDF 标准点每英寸（pt/inch）
     */
    private static final float POINTS_PER_INCH = 72f;

    @Override
    public BoundingBox imageToPdf(BoundingBox imageBox, float dpi, float pdfPageHeightPt) {
        float scale = POINTS_PER_INCH / dpi;
        float x = imageBox.getX() * scale;
        float width = imageBox.getWidth() * scale;
        float height = imageBox.getHeight() * scale;
        float y = pdfPageHeightPt - (imageBox.getY() + imageBox.getHeight()) * scale;
        return BoundingBox.builder()
                .x(x)
                .y(y)
                .width(width)
                .height(height)
                .build();
    }

    /**
     * width/height 非负规范化：负值交换端点，保证 x/y 为左下角、宽高非负。
     */
    private static BoundingBox canonicalize(BoundingBox box) {
        float w = box.getWidth();
        float h = box.getHeight();
        if (w >= 0 && h >= 0) {
            return box;
        }
        float x = w < 0 ? box.getX() + w : box.getX();
        float y = h < 0 ? box.getY() + h : box.getY();
        return BoundingBox.builder()
                .x(x)
                .y(y)
                .width(Math.abs(w))
                .height(Math.abs(h))
                .build();
    }

    @Override
    public BoundingBox pdfToImage(BoundingBox pdfBox, float dpi, float pdfPageHeightPt) {
        float k = dpi / POINTS_PER_INCH;
        return BoundingBox.builder()
                .x(pdfBox.getX() * k)
                .y((pdfPageHeightPt - (pdfBox.getY() + pdfBox.getHeight())) * k)
                .width(pdfBox.getWidth() * k)
                .height(pdfBox.getHeight() * k)
                .build();
    }

    @Override
    public BoundingBox normalize(BoundingBox box, CoordinateSystem source,
                                 float dpi, float pdfPageHeightPt) {
        // 仅负责：坐标系归一 + 非负规范化；不做旋转/裁剪/边界裁切
        BoundingBox canonical = canonicalize(box);
        if (source == CoordinateSystem.IMAGE_PIXEL_SPACE) {
            return imageToPdf(canonical, dpi, pdfPageHeightPt);
        }
        return canonical;
    }
}
