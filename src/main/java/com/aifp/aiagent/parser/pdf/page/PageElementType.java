package com.aifp.aiagent.parser.pdf.page;

/**
 * 页面元素类型。
 * <p>
 * 本阶段仅支持 TEXT 与 PENDING_OCR 两值；结构化视觉元素（IMAGE_REGION 等）
 * 随阶段 5/6 区域识别扩展，AST 级完整元素体系由阶段 9 设计，此处刻意保持轻量。
 *
 * @author Tang_tzb
 */
public enum PageElementType {

    /**
     * 文字元素（来自 PDF 文字层或后续 OCR）
     */
    TEXT("TEXT", "文字元素"),

    /**
     * 待 OCR 处理的占位元素（明确标记待处理区域与接入阶段）
     */
    PENDING_OCR("PENDING_OCR", "待OCR占位");

    private final String code;
    private final String label;

    PageElementType(String code, String label) {
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
