package com.aifp.aiagent.parser.pdf.structure;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 标题识别器（阶段 9，对应《PDF解析改造方案》§二十六 structure 包）。
 * <p>
 * 启发式（阶段 9 最小可用）：
 * <ul>
 *   <li>字号主判定：主字号 ≥ 页内 PDF_TEXT 元素中位字号 × {@code title-font-ratio}；</li>
 *   <li>长度约束：文本 ≤ {@value #TITLE_MAX_LENGTH} 字符（排除大字号正文段）；</li>
 *   <li>OCR 元素无字号信息，永不判题（fontSize=null 直接拒绝）。</li>
 * </ul>
 * 阶段 10 清洗 / 后续字体加粗等视觉特征可在此扩展。
 *
 * @author Tang_tzb
 */
@Component
public class TitleRecognizer {

    /**
     * 标题最大字符数（超过视为正文段）
     */
    private static final int TITLE_MAX_LENGTH = 50;

    /**
     * 标题字号倍数阈值：fontSize ≥ 中位字号 × 该值视为标题
     */
    @Value("${document.parser.pdf.structure.title-font-ratio:1.2}")
    private double titleFontRatio;

    /**
     * 判定文本块是否为标题。
     *
     * @param text           文本内容
     * @param fontSize       主字号（OCR 来源为 null）
     * @param medianFontSize 页内 PDF_TEXT 元素中位字号（无字号数据时调用方不应调用，
     *                       此处 ≤0 兜底拒绝）
     * @return true 表示标题
     */
    public boolean isTitle(String text, Float fontSize, float medianFontSize) {
        if (text == null || text.isBlank() || fontSize == null || medianFontSize <= 0) {
            return false;
        }
        String trimmed = text.strip();
        return trimmed.length() <= TITLE_MAX_LENGTH
                && fontSize >= medianFontSize * titleFontRatio;
    }
}
