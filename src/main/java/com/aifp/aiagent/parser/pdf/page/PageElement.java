package com.aifp.aiagent.parser.pdf.page;

import com.aifp.aiagent.parser.pdf.region.RegionType;
import com.aifp.aiagent.parser.pdf.text.BoundingBox;
import lombok.Builder;
import lombok.Data;

/**
 * 页面元素：页内最小内容单元的轻量占位模型。
 * <p>
 * 阶段 3 起携带结构化文字信息（bbox/字号/字体）；表格结构、AST 级元素体系
 * 分别由阶段 7 与阶段 9 扩展。字段约定（按数据来源区分）：
 * <ul>
 *   <li>TEXT（PDF_TEXT 来源）：text 必填，bbox/fontSize/fontName 必填（来自 TextBlock），
 *       confidence/regionType 为 null；</li>
 *   <li>TEXT（OCR 来源，阶段 4/6）：text/bbox 必填（bbox 经 CoordinateTransformer
 *       换算为 PDF 用户空间）；fontSize/fontName 为 null（Tesseract 不输出字体信息）；
 *       confidence = 行内词置信度均值，regionType = 来源视觉区域类型；</li>
 *   <li>IMAGE_REGION 元素（阶段 6）：text 恒为空串，bbox/regionType/description 必填，
 *       fontSize/fontName/confidence 为 null。</li>
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
     * 文本内容（IMAGE_REGION 元素恒为空串）
     */
    private String text;

    /**
     * 说明信息（PENDING_OCR / IMAGE_REGION 使用）
     */
    private String description;

    /**
     * 外接矩形（PDF 用户空间；来自 TextBlock / OCR 换算 / 区域占位）
     */
    private BoundingBox bbox;

    /**
     * 主字号 pt（来自 TextBlock；OCR/IMAGE_REGION 为 null）
     */
    private Float fontSize;

    /**
     * 主字体名（来自 TextBlock；OCR/IMAGE_REGION 为 null）
     */
    private String fontName;

    /**
     * 来源视觉区域类型（仅 OCR 元素与 IMAGE_REGION 元素非空，阶段 6）
     */
    private RegionType regionType;

    /**
     * 识别置信度（0~100；OCR 行元素 = 行内词置信度均值，其余来源为 null）
     */
    private Float confidence;
}
