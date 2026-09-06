package com.aifp.aiagent.parser.pdf.text;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * {@link SimpleCoordinateTransformer} 纯函数测试：
 * 像素框（top-left 原点）→ PDF 用户空间框（bottom-left 原点）。
 *
 * @author Tang_tzb
 */
class SimpleCoordinateTransformerTest {

    private static final float PAGE_H = 842f;
    private final SimpleCoordinateTransformer transformer = new SimpleCoordinateTransformer();

    @Test
    void imageToPdf_scalesAndFlipsY() {
        // dpi=200 → scale=72/200=0.36
        BoundingBox pixelBox = BoundingBox.builder()
                .x(100).y(50).width(200).height(40).build();

        BoundingBox pdf = transformer.imageToPdf(pixelBox, 200f, PAGE_H);

        assertThat(pdf.getX()).isEqualTo(36f, within(0.0001f));
        assertThat(pdf.getWidth()).isEqualTo(72f, within(0.0001f));
        assertThat(pdf.getHeight()).isEqualTo(14.4f, within(0.0001f));
        // y' = 842 − (50+40)×0.36 = 809.6
        assertThat(pdf.getY()).isEqualTo(809.6f, within(0.0001f));
    }

    @Test
    void imageToPdf_topEdgeMapsToPageTop() {
        // 像素框顶端 y=0 → 用户空间框顶 = pageHeight
        BoundingBox pixelBox = BoundingBox.builder()
                .x(0).y(0).width(100).height(50).build();

        BoundingBox pdf = transformer.imageToPdf(pixelBox, 200f, PAGE_H);

        assertThat(pdf.top()).isEqualTo(PAGE_H, within(0.0001f));
        assertThat(pdf.getY()).isEqualTo(PAGE_H - 50 * 0.36f, within(0.0001f));
    }

    @Test
    void imageToPdf_bottomEdgeMapsToPageBottom() {
        // dpi=72 → scale=1（像素即 pt），像素框底边贴图底 → 用户空间 y'=0（精确断言）
        BoundingBox pixelBox = BoundingBox.builder()
                .x(0).y(842 - 40).width(100).height(40).build();

        BoundingBox pdf = transformer.imageToPdf(pixelBox, 72f, PAGE_H);

        assertThat(pdf.getY()).isEqualTo(0f, within(0.0001f));
        // 框底贴页底（y'=0），框顶 = 框高 40pt
        assertThat(pdf.top()).isEqualTo(40f, within(0.0001f));
    }
}
