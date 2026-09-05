package com.aifp.aiagent.parser.pdf.page;

/**
 * 页面元素数据来源（对应《PDF解析改造方案》第十四节来源追踪设计）。
 * <p>
 * 枚举值一次性冻结，后续阶段只扩展值不改动语义：
 * OCR 值由阶段 4 填充，IMAGE 值由阶段 5/6 填充，FUSION 值由阶段 8 填充。
 *
 * @author Tang_tzb
 */
public enum ElementSource {

    /**
     * PDF 原生文字层
     */
    PDF_TEXT("PDF_TEXT", "PDF文字层"),

    /**
     * OCR 识别结果
     */
    OCR("OCR", "OCR识别"),

    /**
     * PDF 内嵌图片区域
     */
    IMAGE("IMAGE", "图片区域"),

    /**
     * 多源融合结果（文字与视觉结构合并）
     */
    FUSION("FUSION", "融合结果");

    private final String code;
    private final String label;

    ElementSource(String code, String label) {
        this.code = code;
        this.label = label;
    }

    public String getCode() {
        return code;
    }

    public String getLabel() {
        return label;
    }
}
