package com.aifp.aiagent.parser.pdf.text;

/**
 * 坐标转换抽象：渲染图像素空间 ↔ PDF 用户空间。
 * <p>
 * 阶段 4 提前引入：ImagePageParser 不内嵌任何坐标公式，词级像素框经本接口
 * 换算为与 TextPageParser 同系的 PDF 用户空间框。
 * <b>阶段 4 仅提供简单实现</b>（{@link SimpleCoordinateTransformer}：等比 DPI
 * 换算 + Y 轴翻转）；阶段 5"统一坐标系统"在本接口上扩展 pdfToImage()/
 * normalize() 并处理旋转页，不重建抽象。
 *
 * @author Tang_tzb
 */
public interface CoordinateTransformer {

    /**
     * 渲染图像素框（top-left 原点，y 向下）→ PDF 用户空间框（bottom-left 原点，y 向上）。
     * <p>
     * 入参复用 {@link BoundingBox} 承载像素框（仅作数值容器，x/y 为像素框左上角，
     * width/height 为像素尺寸）；出参为标准 PDF 用户空间矩形。
     *
     * @param imageBox        图像像素框（top-left 原点）
     * @param dpi             渲染 DPI（像素与 pt 的换算基准）
     * @param pdfPageHeightPt PDF 页高（pt，Y 轴翻转基准）
     * @return PDF 用户空间框（bottom-left 原点，y 向上）
     */
    BoundingBox imageToPdf(BoundingBox imageBox, float dpi, float pdfPageHeightPt);
}
