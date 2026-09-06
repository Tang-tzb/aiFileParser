package com.aifp.aiagent.parser.pdf.text;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link BoundingBox} 几何谓词测试（PDF 用户空间语义，y 向上）。
 * 口径：边缘相接不算重叠；点包含右/上开区间。
 *
 * @author Tang_tzb
 */
class BoundingBoxTest {

    private static BoundingBox box(float x, float y, float w, float h) {
        return BoundingBox.builder().x(x).y(y).width(w).height(h).build();
    }

    // ---------- contains(BoundingBox) ----------

    @Test
    void contains_nested() {
        assertThat(box(0, 0, 100, 100).contains(box(10, 10, 50, 50))).isTrue();
    }

    @Test
    void contains_touchingEdge() {
        // 边界相接算包含（闭区间）
        assertThat(box(0, 0, 100, 100).contains(box(0, 0, 100, 100))).isTrue();
        assertThat(box(0, 0, 100, 100).contains(box(100, 100, 0, 0))).isTrue();
    }

    @Test
    void contains_separated() {
        assertThat(box(0, 0, 100, 100).contains(box(50, 0, 100, 100))).isFalse();
        assertThat(box(0, 0, 100, 100).contains(box(10, 10, 50, 200))).isFalse();
    }

    // ---------- contains(point) ----------

    @Test
    void containsPoint_leftBottomInclusive_rightTopExclusive() {
        BoundingBox b = box(10, 10, 50, 50);
        assertThat(b.contains(10, 10)).isTrue();    // 左下角含
        assertThat(b.contains(59.9f, 59.9f)).isTrue();
        assertThat(b.contains(60, 30)).isFalse();   // 右边界开区间
        assertThat(b.contains(30, 60)).isFalse();   // 上边界开区间
        assertThat(b.contains(9.9f, 30)).isFalse();
    }

    // ---------- overlap ----------

    @Test
    void overlap_partial() {
        assertThat(box(0, 0, 10, 10).overlap(box(5, 0, 10, 10))).isTrue();
        assertThat(box(0, 0, 10, 10).overlap(box(5, 5, 10, 10))).isTrue();   // 角部交
        assertThat(box(0, 0, 10, 10).overlap(box(2, 2, 3, 3))).isTrue();     // 嵌套
    }

    @Test
    void overlap_edgeTouchingOrSeparated() {
        // 边缘相接 = 不重叠（严格面积 > 0 口径）
        assertThat(box(0, 0, 10, 10).overlap(box(10, 0, 10, 10))).isFalse();
        assertThat(box(0, 0, 10, 10).overlap(box(0, 10, 10, 10))).isFalse();
        assertThat(box(0, 0, 10, 10).overlap(box(20, 20, 10, 10))).isFalse();
    }

    // ---------- intersection ----------

    @Test
    void intersection_exactValues() {
        BoundingBox inter = box(0, 0, 10, 10).intersection(box(5, 3, 10, 10));
        assertThat(inter).isNotNull();
        assertThat(inter.getX()).isEqualTo(5f);
        assertThat(inter.getY()).isEqualTo(3f);
        assertThat(inter.getWidth()).isEqualTo(5f);
        assertThat(inter.getHeight()).isEqualTo(7f);
    }

    @Test
    void intersection_nested_returnsSmaller() {
        BoundingBox inter = box(0, 0, 100, 100).intersection(box(10, 10, 50, 50));
        assertThat(inter).isEqualTo(box(10, 10, 50, 50));
    }

    @Test
    void intersection_none_returnsNull() {
        assertThat(box(0, 0, 10, 10).intersection(box(10, 0, 10, 10))).isNull();
        assertThat(box(0, 0, 10, 10).intersection(box(20, 20, 10, 10))).isNull();
    }

    // ---------- iou ----------

    @Test
    void iou_identical_isOne() {
        assertThat(box(0, 0, 10, 10).iou(box(0, 0, 10, 10))).isEqualTo(1.0);
    }

    @Test
    void iou_halfOverlap() {
        // A=(0,0,10,10), B=(5,0,10,10)：交 50，并 150，IoU = 1/3
        assertThat(box(0, 0, 10, 10).iou(box(5, 0, 10, 10))).isCloseTo(1.0 / 3.0,
                org.assertj.core.data.Offset.offset(1e-9));
    }

    @Test
    void iou_separatedOrZeroArea_isZero() {
        assertThat(box(0, 0, 10, 10).iou(box(50, 50, 10, 10))).isEqualTo(0.0);
        assertThat(box(0, 0, 10, 0).iou(box(0, 0, 10, 10))).isEqualTo(0.0);  // 零面积
    }

    // ---------- expand ----------

    @Test
    void expand_growsSymmetrically() {
        BoundingBox expanded = box(10, 10, 50, 50).expand(5);
        assertThat(expanded.getX()).isEqualTo(5f);
        assertThat(expanded.getY()).isEqualTo(5f);
        assertThat(expanded.getWidth()).isEqualTo(60f);
        assertThat(expanded.getHeight()).isEqualTo(60f);
    }

    @Test
    void expand_negativeShrinks_allowingDegenerate() {
        BoundingBox shrunk = box(0, 0, 10, 10).expand(-3);
        assertThat(shrunk.getX()).isEqualTo(3f);
        assertThat(shrunk.getY()).isEqualTo(3f);
        assertThat(shrunk.getWidth()).isEqualTo(4f);
        assertThat(shrunk.getHeight()).isEqualTo(4f);
        // 纯数学运算，不裁剪退化矩形
        BoundingBox degenerate = box(0, 0, 4, 4).expand(-3);
        assertThat(degenerate.getWidth()).isEqualTo(-2f);
    }

    // ---------- union 回归 ----------

    @Test
    void union_regression() {
        BoundingBox u = box(0, 0, 10, 10).union(box(5, 5, 10, 10));
        assertThat(u.getX()).isEqualTo(0f);
        assertThat(u.getY()).isEqualTo(0f);
        assertThat(u.getWidth()).isEqualTo(15f);
        assertThat(u.getHeight()).isEqualTo(15f);
    }
}
