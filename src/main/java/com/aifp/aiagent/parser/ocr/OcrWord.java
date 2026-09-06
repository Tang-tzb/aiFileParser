package com.aifp.aiagent.parser.ocr;

import com.aifp.aiagent.parser.pdf.page.ElementSource;
import lombok.Builder;
import lombok.Data;

/**
 * OCR 识别词：词级最小识别单元。
 * <p>
 * 坐标为渲染图像素空间（top-left 原点，y 向下），保留引擎原始输出值，
 * 统一换算由 CoordinateTransformer 负责（阶段 4 简单实现 / 阶段 5 扩展）。
 * <p>
 * <b>lineNo 语义边界</b>：仅为 OCR 引擎原始布局元数据（TSV block/par/line
 * 变化沿推导的页内行序号），阶段 4 仅用于行分组输出；后续阶段不得将其
 * 直接用作最终 Layout 行模型——阶段 6+ LayoutAnalyzer 须独立建立行模型。
 *
 * @author Tang_tzb
 */
@Data
@Builder
public class OcrWord {

    /**
     * 识别文本
     */
    private String text;

    /**
     * 词外接矩形左上角 X（图像像素空间）
     */
    private int x;

    /**
     * 词外接矩形左上角 Y（图像像素空间）
     */
    private int y;

    /**
     * 词宽（px）
     */
    private int width;

    /**
     * 词高（px）
     */
    private int height;

    /**
     * 识别置信度（0~100）
     */
    private float confidence;

    /**
     * 页码（1-based）
     */
    private int page;

    /**
     * 数据来源（恒为 OCR）
     */
    private ElementSource source;

    /**
     * 页内行序号（引擎原始布局元数据，仅用于行分组，见类注释边界说明）
     */
    private int lineNo;
}
