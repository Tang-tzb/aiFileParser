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
}
