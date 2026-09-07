package com.aifp.aiagent.parser.pdf.ast;

import com.aifp.aiagent.parser.pdf.page.ElementSource;
import com.aifp.aiagent.parser.pdf.text.BoundingBox;
import lombok.Getter;

/**
 * 标题节点（阶段 9）：字号中位数启发式识别的大字号短文本块。
 * <p>
 * 仅源自 PDF 原生文字（OCR 行无字号信息，永不判题），
 * 故 source 恒为 PDF_TEXT、confidence 恒 1.0（§十四）。
 *
 * @author Tang_tzb
 */
@Getter
public class TitleNode extends DocumentNode {

    /**
     * 标题文本（已保留原始换行）
     */
    private final String text;

    /**
     * 主字号 pt（标题判定依据）
     */
    private final Float fontSize;

    /**
     * 主字体名
     */
    private final String fontName;

    public TitleNode(String text, Float fontSize, String fontName, BoundingBox bbox) {
        super(DocumentNodeType.TITLE, ElementSource.PDF_TEXT, 1.0f, bbox, null);
        this.text = text;
        this.fontSize = fontSize;
        this.fontName = fontName;
    }
}
