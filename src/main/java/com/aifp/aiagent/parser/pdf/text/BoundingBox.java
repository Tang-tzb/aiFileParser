package com.aifp.aiagent.parser.pdf.text;

import lombok.Builder;
import lombok.Data;

/**
 * 通用矩形：PDF 用户空间坐标（原点左下，y 轴向上，单位 pt）。
 * <p>
 * 统一坐标系约定：文字（本包）与图片占位（阶段 1 检测引擎 CTM）均以
 * PDF 用户空间存储，为阶段 8 文字/视觉坐标融合提供同系坐标。
 *
 * @author Tang_tzb
 */
@Data
@Builder
public class BoundingBox {

    /**
     * 左下角 X（pt）
     */
    private float x;

    /**
     * 左下角 Y（pt）
     */
    private float y;

    /**
     * 宽度（pt）
     */
    private float width;

    /**
     * 高度（pt）
     */
    private float height;

    /**
     * 右边界
     */
    public float right() {
        return x + width;
    }

    /**
     * 上边界
     */
    public float top() {
        return y + height;
    }

    /**
     * 合并两个矩形（取并集的外接矩形）。
     */
    public BoundingBox union(BoundingBox other) {
        float minX = Math.min(x, other.x);
        float minY = Math.min(y, other.y);
        float maxX = Math.max(right(), other.right());
        float maxY = Math.max(top(), other.top());
        return BoundingBox.builder()
                .x(minX)
                .y(minY)
                .width(maxX - minX)
                .height(maxY - minY)
                .build();
    }

    /**
     * 是否完全包含另一矩形（边界相接算包含）。
     */
    public boolean contains(BoundingBox other) {
        return x <= other.x && y <= other.y
                && right() >= other.right() && top() >= other.top();
    }

    /**
     * 是否包含点（左/下闭区间，右/上开区间）。
     */
    public boolean contains(float px, float py) {
        return x <= px && px < right() && y <= py && py < top();
    }

    /**
     * 是否与另一矩形重叠（交集面积 > 0；边缘相接不算重叠）。
     */
    public boolean overlap(BoundingBox other) {
        return Math.min(right(), other.right()) > Math.max(x, other.x)
                && Math.min(top(), other.top()) > Math.max(y, other.y);
    }

    /**
     * 与另一矩形的交集矩形；无重叠（含边缘相接）返回 null。
     */
    public BoundingBox intersection(BoundingBox other) {
        float ix = Math.max(x, other.x);
        float iy = Math.max(y, other.y);
        float ir = Math.min(right(), other.right());
        float it = Math.min(top(), other.top());
        if (ir <= ix || it <= iy) {
            return null;
        }
        return BoundingBox.builder()
                .x(ix)
                .y(iy)
                .width(ir - ix)
                .height(it - iy)
                .build();
    }

    /**
     * 与另一矩形的交并比 IoU ∈ [0,1]；任一矩形零面积时为 0。
     */
    public double iou(BoundingBox other) {
        BoundingBox inter = intersection(other);
        if (inter == null) {
            return 0d;
        }
        double interArea = (double) inter.width * inter.height;
        double unionArea = (double) width * height
                + (double) other.width * other.height - interArea;
        return unionArea <= 0 ? 0d : interArea / unionArea;
    }

    /**
     * 四周外扩 margin（负值内缩；纯数学运算，不裁剪退化矩形，调用方自理）。
     */
    public BoundingBox expand(float margin) {
        return BoundingBox.builder()
                .x(x - margin)
                .y(y - margin)
                .width(width + 2 * margin)
                .height(height + 2 * margin)
                .build();
    }
}
