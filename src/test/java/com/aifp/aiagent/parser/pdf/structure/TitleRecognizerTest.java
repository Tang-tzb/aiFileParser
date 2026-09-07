package com.aifp.aiagent.parser.pdf.structure;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link TitleRecognizer} 单元测试（阶段 9）：
 * 字号中位数倍数主判定、长度约束、OCR 无字号拒判、边界与兜底。
 *
 * @author Tang_tzb
 */
class TitleRecognizerTest {

    private TitleRecognizer recognizer;

    @BeforeEach
    void setUp() {
        recognizer = new TitleRecognizer();
        // 离线注入 @Value 阈值（与 PdfPageTestSupport 同惯例）
        ReflectionTestUtils.setField(recognizer, "titleFontRatio", 1.2);
    }

    @Test
    void bigFontShortText_isTitle() {
        // 16pt ≥ 12pt×1.2=14.4 且 ≤50 字符 → 标题
        assertThat(recognizer.isTitle("施工许可证", 16.0f, 12.0f)).isTrue();
    }

    @Test
    void sameFontAsBody_notTitle() {
        // 12pt = 中位字号 → 非标题
        assertThat(recognizer.isTitle("正文段落内容", 12.0f, 12.0f)).isFalse();
    }

    @Test
    void longText_notTitle() {
        // 大字号但超长（60 字符）→ 正文段
        String longText = "长".repeat(60);
        assertThat(recognizer.isTitle(longText, 16.0f, 12.0f)).isFalse();
    }

    @Test
    void ocrWithoutFontSize_neverTitle() {
        // OCR 行无字号信息 → 永不判题
        assertThat(recognizer.isTitle("扫描标题", null, 12.0f)).isFalse();
    }

    @Test
    void exactRatioBoundary_isTitle() {
        // 边界：fontSize == 中位×倍数（≥）→ 标题
        // 用可精确表示的数值（10×1.5=15.0）规避 1.2 类倍数的浮点表示误差
        ReflectionTestUtils.setField(recognizer, "titleFontRatio", 1.5);
        assertThat(recognizer.isTitle("边界标题", 15.0f, 10.0f)).isTrue();
    }

    @Test
    void zeroMedian_rejected() {
        // 中位字号 ≤0 兜底拒绝（无字号数据页）
        assertThat(recognizer.isTitle("标题", 16.0f, 0f)).isFalse();
    }

    @Test
    void blankOrNullText_rejected() {
        assertThat(recognizer.isTitle("", 16.0f, 12.0f)).isFalse();
        assertThat(recognizer.isTitle("   ", 16.0f, 12.0f)).isFalse();
        assertThat(recognizer.isTitle(null, 16.0f, 12.0f)).isFalse();
    }
}
