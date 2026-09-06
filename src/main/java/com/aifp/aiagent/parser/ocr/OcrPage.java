package com.aifp.aiagent.parser.ocr;

import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * OCR 单页识别结果：词列表 + 渲染元数据。
 * <p>
 * 词按引擎输出顺序存储（阅读序）；行结构由 lineNo 分组辅助方法提供，
 * 不在本模型中固化行模型（lineNo 仅为引擎原始布局元数据）。
 *
 * @author Tang_tzb
 */
@Data
@Builder
public class OcrPage {

    /**
     * 页码（1-based）
     */
    private int pageNumber;

    /**
     * 渲染图片宽度（px）
     */
    private int imageWidth;

    /**
     * 渲染图片高度（px）
     */
    private int imageHeight;

    /**
     * 渲染 DPI（词级像素坐标的参照系）
     */
    private int dpi;

    /**
     * 词列表（阅读序）
     */
    private List<OcrWord> words;
}
