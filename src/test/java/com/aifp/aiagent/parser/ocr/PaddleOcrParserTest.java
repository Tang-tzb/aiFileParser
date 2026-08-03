package com.aifp.aiagent.parser.ocr;

import org.junit.jupiter.api.Test;

import java.io.File;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link PaddleOcrParser} 桩行为测试
 *
 * @author aiFileParser
 */
class PaddleOcrParserTest {

    private final PaddleOcrParser parser = new PaddleOcrParser();

    @Test
    void recognize_throwsAsReservedStub() {
        assertThatThrownBy(() -> parser.recognize(new File("dummy.png")))
                .isInstanceOf(UnsupportedOperationException.class)
                .hasMessageContaining("OCR");
    }
}
