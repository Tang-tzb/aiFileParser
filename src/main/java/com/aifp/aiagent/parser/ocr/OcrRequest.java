package com.aifp.aiagent.parser.ocr;

import lombok.Builder;
import lombok.Data;

import java.io.File;

/**
 * OCR 识别请求：一次单页图片识别的全部输入。
 * <p>
 * 字段全部为引擎无关原语——新增 OCR 引擎实现 {@link OcrParser} 时
 * 请求模型零改动（多引擎扩展约定）。图像元数据由渲染方提供，
 * 识别实现无需二次解码图片。
 *
 * @author Tang_tzb
 */
@Data
@Builder
public class OcrRequest {

    /**
     * 待识别的页面图片文件（调用方负责生命周期，识别层只读）
     */
    private File imageFile;

    /**
     * 页码（1-based，对齐 PDF 惯例）
     */
    private int pageNumber;

    /**
     * 图片宽度（px）
     */
    private int imageWidth;

    /**
     * 图片高度（px）
     */
    private int imageHeight;

    /**
     * 渲染 DPI（词级像素坐标的参照系，供结果元数据与下游坐标换算）
     */
    private int dpi;
}
