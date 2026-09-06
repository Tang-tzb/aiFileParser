package com.aifp.aiagent.parser.ocr;

/**
 * OCR 解析器接口：引擎无关的词级识别抽象（多引擎扩展点）。
 * <p>
 * OCR 不是文件类型而是一种能力（识别扫描件/图片中的文本），不参与
 * {@link com.aifp.aiagent.parser.FileParser} 的 FileType 策略选择。
 * <p>
 * 多引擎扩展约定：接口签名与 {@link OcrRequest}/{@link OcrResult} 模型
 * 均为引擎无关，新增引擎（如未来 PaddleOCR）实现本接口并经
 * {@code document.parser.pdf.ocr.engine} 配置 + 条件装配接入即可。
 * 阶段 4 标准化识别实现为 {@link TesseractOcrParser}（Tesseract CLI + TSV）。
 *
 * @author Tang_tzb
 */
public interface OcrParser {

    /**
     * 识别单页图片，返回含状态、词级坐标与置信度的结构化结果。
     * <p>
     * 实现约定：不抛业务异常——所有进程异常、超时、中断均在引擎层
     * 收敛为 {@link OcrStatus#FAILED} + errorMessage 状态化返回，
     * 错误语义（是否中断流程）由调用方（解析层）决定。
     *
     * @param request 识别请求（图片文件 + 页码 + 渲染元数据）
     * @return 结构化识别结果（含状态三态）
     */
    OcrResult recognize(OcrRequest request);
}
