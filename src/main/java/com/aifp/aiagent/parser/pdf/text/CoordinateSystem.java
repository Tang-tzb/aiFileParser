package com.aifp.aiagent.parser.pdf.text;

/**
 * 坐标系枚举（阶段 5 统一坐标系统）。
 * <p>
 * 规范坐标系为 {@link #PDF_USER_SPACE}：全管线（TextPageParser/ImagePageParser/
 * 后续融合阶段）的框坐标统一归一到该系后比较。
 *
 * @author Tang_tzb
 */
public enum CoordinateSystem {

    /**
     * PDF 用户空间（规范坐标系）：bottom-left 原点，y 向上，单位 pt。
     */
    PDF_USER_SPACE,

    /**
     * 渲染图像素空间：top-left 原点，y 向下，单位 px
     * （渲染产物与 OCR 词坐标所在系）。
     */
    IMAGE_PIXEL_SPACE
}
