package com.aifp.aiagent.parser.ocr;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.File;

/**
 * PaddleOCR 解析器桩实现（预留接入点）
 * <p>
 * 本阶段未接入真实 OCR，调用即抛 {@link UnsupportedOperationException}，
 * 作为后续 PaddleOCR 集成的占位 bean。
 *
 * @author aiFileParser
 */
@Slf4j
@Component
public class PaddleOcrParser implements OcrParser {

    @Override
    public String recognize(File file) {
        log.warn("OCR 未实现，预留 PaddleOCR 接入点: {}", file.getName());
        throw new UnsupportedOperationException("OCR 未实现，预留 PaddleOCR 接入点");
    }
}
