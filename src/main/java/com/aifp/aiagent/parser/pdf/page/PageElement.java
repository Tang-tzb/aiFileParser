package com.aifp.aiagent.parser.pdf.page;

import com.aifp.aiagent.parser.pdf.text.BoundingBox;
import lombok.Builder;
import lombok.Data;

/**
 * 页面元素：页内最小内容单元的轻量占位模型。
 * <p>
 * 阶段 3 起携带结构化文字信息（bbox/字号/字体）；表格结构、AST 级元素体系
 * 分别由阶段 5/6 与阶段 9 扩展。字段约定：
 * <ul>
 *   <li>TEXT 元素：text 必填，bbox/fontSize/fontName 必填（来自 TextBlock）；</li>
 *   <li>PENDING_OCR 元素：text 恒为空串，description 必填，bbox/fontSize/fontName 为 null。</li>
 * </ul>
 *
 * @author Tang_tzb
 */
@Data
@Builder
public class PageElement {

    /**
     * 元素类型
     */
    private PageElementType type;

    /**
     * 数据来源（PDF 文字层 / OCR / 图片 / 融合）
     */
    private ElementSource source;

    /**
     * 文本内容（PENDING_OCR 元素恒为空串）
     */
    private String text;

    /**
     * 说明信息（仅 PENDING_OCR 使用）
     */
    private String description;

    /**
     * 外接矩形（PDF 用户空间，来自 TextBlock；PENDING_OCR 为 null）
     */
    private BoundingBox bbox;

    /**
     * 主字号 pt（来自 TextBlock；PENDING_OCR 为 null）
     */
    private Float fontSize;

    /**
     * 主字体名（来自 TextBlock；PENDING_OCR 为 null）
     */
    private String fontName;
}
