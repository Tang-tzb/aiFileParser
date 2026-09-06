package com.aifp.aiagent.parser.pdf.text;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * {@link SimpleCoordinateTransformer} 纯函数测试：
 * 像素框（top-left 原点）↔ PDF 用户空间框（bottom-left 原点）双向换算与归一。
 * 页面几何基准：MediaBox + 原点(0,0) + rotation=0。
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

    // ---------- 阶段 5：pdfToImage ----------

    /**
     * 验收数字用例：PDF 595×842 @300dpi → 2480×3508（整页）。
     * 理论值 2479.17×3508.33，渲染器取整后即 2480×3508；断言 ±2px。
     */
    @Test
    void pdfToImage_acceptanceCase_595x842_at300dpi() {
        BoundingBox page = BoundingBox.builder().x(0).y(0).width(595).height(842).build();

        BoundingBox pixel = transformer.pdfToImage(page, 300f, 842f);

        assertThat(pixel.getX()).isCloseTo(0f, within(0.01f));
        assertThat(pixel.getY()).isCloseTo(0f, within(0.01f));
        assertThat(pixel.getWidth()).isCloseTo(595f * 300f / 72f, within(2f));   // 2479.17
        assertThat(pixel.getHeight()).isCloseTo(842f * 300f / 72f, within(2f)); // 3508.33
    }

    /**
     * 已知点换算：PDF (100,700)（零尺寸框）→ 像素 (416.7, 591.7)。
     */
    @Test
    void pdfToImage_knownPoint() {
        BoundingBox point = BoundingBox.builder().x(100).y(700).width(0).height(0).build();

        BoundingBox pixel = transformer.pdfToImage(point, 300f, 842f);

        assertThat(pixel.getX()).isCloseTo(416.67f, within(0.01f));
        assertThat(pixel.getY()).isCloseTo(591.67f, within(0.01f));
    }

    // ---------- 阶段 5：DPI 矩阵 + roundtrip 契约 ----------

    /**
     * DPI 矩阵（72/150/200/300）：双向换算精确 + roundtrip 契约 ε=1e-3。
     */
    @ParameterizedTest
    @ValueSource(ints = {72, 150, 200, 300})
    void roundtripContract_acrossDpiMatrix(int dpi) {
        float d = dpi;
        BoundingBox page = BoundingBox.builder().x(0).y(0).width(595).height(842).build();
        BoundingBox sample = BoundingBox.builder().x(100).y(700).width(200).height(120).build();

        // 正向精确值
        BoundingBox pixel = transformer.pdfToImage(sample, d, 842f);
        assertThat(pixel.getX()).isCloseTo(100f * d / 72f, within(1e-3f));
        assertThat(pixel.getWidth()).isCloseTo(200f * d / 72f, within(1e-3f));
        assertThat(pixel.getHeight()).isCloseTo(120f * d / 72f, within(1e-3f));
        assertThat(pixel.getY()).isCloseTo((842f - 820f) * d / 72f, within(1e-3f));

        // 整页换算
        BoundingBox pagePixel = transformer.pdfToImage(page, d, 842f);
        assertThat(pagePixel.getWidth()).isCloseTo(595f * d / 72f, within(1e-3f));
        assertThat(pagePixel.getHeight()).isCloseTo(842f * d / 72f, within(1e-3f));

        // roundtrip 双向契约
        BoundingBox backToPdf = transformer.imageToPdf(pixel, d, 842f);
        assertThat(backToPdf.getX()).isCloseTo(sample.getX(), within(1e-3f));
        assertThat(backToPdf.getY()).isCloseTo(sample.getY(), within(1e-3f));
        assertThat(backToPdf.getWidth()).isCloseTo(sample.getWidth(), within(1e-3f));
        assertThat(backToPdf.getHeight()).isCloseTo(sample.getHeight(), within(1e-3f));

        BoundingBox backToPixel = transformer.pdfToImage(
                transformer.imageToPdf(pixel, d, 842f), d, 842f);
        assertThat(backToPixel.getX()).isCloseTo(pixel.getX(), within(1e-3f));
        assertThat(backToPixel.getY()).isCloseTo(pixel.getY(), within(1e-3f));
        assertThat(backToPixel.getWidth()).isCloseTo(pixel.getWidth(), within(1e-3f));
        assertThat(backToPixel.getHeight()).isCloseTo(pixel.getHeight(), within(1e-3f));
    }

    // ---------- 阶段 5：非 A4 页面 ----------

    /**
     * 非 A4：LETTER（612×792）与自定义小票式（430×842）换算 + roundtrip。
     */
    @ParameterizedTest
    @ValueSource(floats = {612f, 430f})
    void pdfToImage_andRoundtrip_nonA4Pages(float pageW) {
        float pageH = pageW == 612f ? 792f : 842f;
        int dpi = 300;
        BoundingBox box = BoundingBox.builder().x(50).y(600).width(150).height(100).build();

        BoundingBox pixel = transformer.pdfToImage(box, dpi, pageH);
        assertThat(pixel.getWidth()).isCloseTo(150f * dpi / 72f, within(1e-3f));
        assertThat(pixel.getHeight()).isCloseTo(100f * dpi / 72f, within(1e-3f));

        BoundingBox back = transformer.imageToPdf(pixel, dpi, pageH);
        assertThat(back.getX()).isCloseTo(50f, within(1e-3f));
        assertThat(back.getY()).isCloseTo(600f, within(1e-3f));
        assertThat(back.getWidth()).isCloseTo(150f, within(1e-3f));
        assertThat(back.getHeight()).isCloseTo(100f, within(1e-3f));
    }

    // ---------- 阶段 5：normalize ----------

    @Test
    void normalize_imagePixel_delegatesToImageToPdf() {
        BoundingBox pixelBox = BoundingBox.builder()
                .x(100).y(50).width(200).height(40).build();

        BoundingBox normalized = transformer.normalize(
                pixelBox, CoordinateSystem.IMAGE_PIXEL_SPACE, 200f, PAGE_H);

        BoundingBox expected = transformer.imageToPdf(pixelBox, 200f, PAGE_H);
        assertThat(normalized.getX()).isCloseTo(expected.getX(), within(1e-6f));
        assertThat(normalized.getY()).isCloseTo(expected.getY(), within(1e-6f));
        assertThat(normalized.getWidth()).isCloseTo(expected.getWidth(), within(1e-6f));
        assertThat(normalized.getHeight()).isCloseTo(expected.getHeight(), within(1e-6f));
    }

    @Test
    void normalize_pdfUserSpace_isIdentity() {
        BoundingBox pdfBox = BoundingBox.builder()
                .x(36).y(500).width(100).height(20).build();

        BoundingBox normalized = transformer.normalize(
                pdfBox, CoordinateSystem.PDF_USER_SPACE, 300f, PAGE_H);

        assertThat(normalized).isEqualTo(pdfBox);
    }

    @Test
    void normalize_canonicalizesNegativeWidthHeight() {
        // 负宽高：交换端点
        BoundingBox negative = BoundingBox.builder()
                .x(10).y(10).width(-5).height(-20).build();

        BoundingBox normalized = transformer.normalize(
                negative, CoordinateSystem.PDF_USER_SPACE, 300f, PAGE_H);

        assertThat(normalized.getX()).isEqualTo(5f);
        assertThat(normalized.getY()).isEqualTo(-10f);
        assertThat(normalized.getWidth()).isEqualTo(5f);
        assertThat(normalized.getHeight()).isEqualTo(20f);
    }

    @Test
    void normalize_doesNotClampToPageBoundary() {
        // 职责边界：不做边界裁切，越界框保持越界
        BoundingBox outOfPage = BoundingBox.builder()
                .x(-50).y(900).width(100).height(50).build();

        BoundingBox normalized = transformer.normalize(
                outOfPage, CoordinateSystem.PDF_USER_SPACE, 300f, PAGE_H);

        assertThat(normalized).isEqualTo(outOfPage);
    }
}
