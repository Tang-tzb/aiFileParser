package com.aifp.aiagent.parser.pdf.text;

import org.springframework.stereotype.Component;

/**
 * 坐标转换简单实现（阶段 4）：等比 DPI 换算 + Y 轴翻转。
 * <p>
 * 换算规则：{@code scale = 72 / dpi}；
 * {@code x' = x·scale}，{@code w' = w·scale}，
 * {@code y' = pageHeight − (y + h)·scale}，{@code h' = h·scale}。
 * 阶段 5 统一坐标系统时在本实现所实现的 {@link CoordinateTransformer}
 * 接口上扩展 pdfToImage()/normalize()/旋转处理。
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
}
