package com.aifp.aiagent.parser.pdf.text;

/**
 * 坐标转换抽象：渲染图像素空间 ↔ PDF 用户空间。
 * <p>
 * 阶段 4 提前引入：ImagePageParser 不内嵌任何坐标公式，词级像素框经本接口
 * 换算为与 TextPageParser 同系的 PDF 用户空间框。
 * <p>
 * 阶段 5 扩展为完整统一坐标能力：双向换算（imageToPdf/pdfToImage）+ 归一
 * （normalize）。<b>页面几何基准（三要素，三个方法统一采用）</b>：
 * MediaBox + 原点(0,0) + rotation=0——与 PageProfile / DefaultPdfTextExtractor
 * 的既有基准一致。已知局限（JavaDoc 显式声明，后续融合阶段统一解决）：
 * rotation≠0 页面换算不成立；CropBox 偏移页不在处理范围。
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

    /**
     * PDF 用户空间框 → 渲染图像素框（top-left 原点，y 向下）。
     * {@link #imageToPdf(BoundingBox, float, float)} 的精确逆变换：
     * 满足 roundtrip 契约 imageToPdf(pdfToImage(b)) ≈ b、
     * pdfToImage(imageToPdf(b)) ≈ b（ε=1e-3，纯浮点误差）。
     *
     * @param pdfBox          PDF 用户空间框
     * @param dpi             渲染 DPI
     * @param pdfPageHeightPt PDF 页高（pt，Y 轴翻转基准）
     * @return 图像像素框（数值容器，同 imageToPdf 入参约定）
     */
    BoundingBox pdfToImage(BoundingBox pdfBox, float dpi, float pdfPageHeightPt);

    /**
     * 任意来源框 → 规范坐标系（PDF 用户空间）。
     * <p>
     * 职责边界（明确限定）：仅做 ①坐标系归一（IMAGE_PIXEL_SPACE → PDF_USER_SPACE）
     * 与 ②width/height 非负规范化（负值交换端点）；
     * <b>不负责旋转、裁剪、边界裁切</b>（不做页面边界 clamp、不处理旋转页），
     * 越界框保持越界。PDF_USER_SPACE 输入 → 仅非负规范化（已是规范系）。
     *
     * @param box             待归一框（source 系语义）
     * @param source          框所在坐标系
     * @param dpi             渲染 DPI（仅 IMAGE_PIXEL_SPACE 来源时生效）
     * @param pdfPageHeightPt PDF 页高 pt（仅 IMAGE_PIXEL_SPACE 来源时生效）
     * @return 规范系（PDF 用户空间）框
     */
    BoundingBox normalize(BoundingBox box, CoordinateSystem source, float dpi, float pdfPageHeightPt);
}
