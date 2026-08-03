package com.aifp.aiagent.parser.ocr;

import java.io.File;

/**
 * OCR 解析器接口（预留 PaddleOCR 接入点）
 * <p>
 * OCR 不是文件类型而是一种能力（识别扫描件/图片中的文本），不参与
 * {@link com.aifp.aiagent.parser.FileParser} 的 FileType 策略选择。
 * 后续接入 PaddleOCR 时实现该接口即可，无需改动现有解析器。
 *
 * @author aiFileParser
 */
public interface OcrParser {

    /**
     * 识别图片/扫描件中的文本。
     *
     * @param file 图片或扫描件
     * @return 识别出的文本
     */
    String recognize(File file);
}
