package com.aifp.aiagent.parser.pdf.ast;

import com.aifp.aiagent.parser.pdf.page.ElementSource;
import com.aifp.aiagent.parser.pdf.text.BoundingBox;
import lombok.Getter;

/**
 * 段落节点（阶段 9）：页内线性文字内容的最小语义单元。
 * <p>
 * 来源约定（§十四）：
 * <ul>
 *   <li>PDF_TEXT：原生文字块，confidence=1.0，fontSize/fontName 透传；</li>
 *   <li>OCR：识别行（如扫描页整页 OCR 结果），confidence 为识别置信度归一 0~1，
 *       fontSize/fontName 恒 null（Tesseract 不输出字体信息）。</li>
 * </ul>
 *
 * @author Tang_tzb
 */
@Getter
public class ParagraphNode extends DocumentNode {

    /**
     * 段落文本
     */
    private final String text;

    /**
     * 主字号 pt（OCR 来源为 null）
     */
    private final Float fontSize;

    /**
     * 主字体名（OCR 来源为 null）
     */
    private final String fontName;

    public ParagraphNode(String text, ElementSource source, Float confidence,
                         BoundingBox bbox, Float fontSize, String fontName) {
        super(DocumentNodeType.PARAGRAPH, source, confidence, bbox, null);
        this.text = text;
        this.fontSize = fontSize;
        this.fontName = fontName;
    }
}
