package com.aifp.aiagent.parser.pdf.text;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link PdfCoordinateConverter} 纯函数测试：top-down dir 坐标 ↔ PDF 用户空间换算。
 *
 * @author Tang_tzb
 */
class PdfCoordinateConverterTest {

    private final PdfCoordinateConverter converter = new PdfCoordinateConverter();

    @Test
    void toUserSpace_convertsTopDownBoxToBottomLeftOrigin() {
        // top-down：距页顶 20pt 处、高 5pt 的字形
        BoundingBox topDown = BoundingBox.builder()
                .x(10f).y(20f).width(30f).height(5f).build();

        BoundingBox user = converter.toUserSpace(topDown, 842f);

        assertThat(user.getX()).isEqualTo(10f);
        assertThat(user.getY()).isEqualTo(842f - 20f - 5f);
        assertThat(user.getWidth()).isEqualTo(30f);
        assertThat(user.getHeight()).isEqualTo(5f);
        // 换算后 top 边界 = pageHeight − topDown.y
        assertThat(user.top()).isEqualTo(842f - 20f);
    }

    @Test
    void toUserSpace_topOfPageMapsToPageHeight() {
        BoundingBox topDown = BoundingBox.builder()
                .x(0f).y(0f).width(10f).height(842f).build();

        BoundingBox user = converter.toUserSpace(topDown, 842f);

        assertThat(user.getY()).isEqualTo(0f);
        assertThat(user.top()).isEqualTo(842f);
    }

    @Test
    void union_mergesDisjointBoxes() {
        BoundingBox a = BoundingBox.builder().x(0f).y(0f).width(10f).height(10f).build();
        BoundingBox b = BoundingBox.builder().x(20f).y(30f).width(5f).height(5f).build();

        BoundingBox union = a.union(b);

        assertThat(union.getX()).isEqualTo(0f);
        assertThat(union.getY()).isEqualTo(0f);
        assertThat(union.right()).isEqualTo(25f);
        assertThat(union.top()).isEqualTo(35f);
    }

    @Test
    void union_containedBoxKeepsOuterBounds() {
        BoundingBox outer = BoundingBox.builder().x(0f).y(0f).width(100f).height(50f).build();
        BoundingBox inner = BoundingBox.builder().x(40f).y(20f).width(10f).height(10f).build();

        BoundingBox union = outer.union(inner);

        assertThat(union.getX()).isEqualTo(0f);
        assertThat(union.getY()).isEqualTo(0f);
        assertThat(union.getWidth()).isEqualTo(100f);
        assertThat(union.getHeight()).isEqualTo(50f);
    }
}
