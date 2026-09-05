package com.aifp.aiagent.parser.pdf;

/**
 * PDF 文档级内容类型
 * <p>
 * 由 {@link PdfAnalyzer} 对全部页面的 {@link PageContentType} 聚合得出。
 * 一个 PDF 可同时存在多种页面类型，聚合规则见 {@link DefaultPdfAnalyzer}。
 *
 * @author Tang_tzb
 */
public enum PdfContentType {

    /**
     * 纯文字 PDF：所有非空白页均为 TEXT_ONLY
     */
    TEXT_ONLY("TEXT_ONLY", "纯文字PDF"),

    /**
     * 整页图片 PDF：所有非空白页均为 IMAGE_ONLY（典型：扫描件）
     */
    IMAGE_ONLY("IMAGE_ONLY", "整页图片PDF"),

    /**
     * 混合 PDF：非空白页存在多种类型，或任一页为 MIXED
     */
    MIXED("MIXED", "图文混合PDF"),

    /**
     * 空 PDF：所有页均为 EMPTY（或无页面）
     */
    EMPTY("EMPTY", "空白PDF");

    private final String code;
    private final String label;

    PdfContentType(String code, String label) {
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
