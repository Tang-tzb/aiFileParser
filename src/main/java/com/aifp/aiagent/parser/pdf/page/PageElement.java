package com.aifp.aiagent.parser.pdf.page;

import lombok.Builder;
import lombok.Data;

/**
 * 页面元素：页内最小内容单元的轻量占位模型。
 * <p>
 * 仅包含来源标识与文本载荷，不含坐标/字体/表格结构（分别由阶段 3 TextBlock、
 * 阶段 5/6 Region/表格识别、阶段 9 Document AST 扩展）。字段约定：
 * <ul>
 *   <li>TEXT 元素：text 必填，description 忽略；</li>
 *   <li>PENDING_OCR 元素：text 恒为空串，description 必填（写明待处理内容与接入阶段）。</li>
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
}
