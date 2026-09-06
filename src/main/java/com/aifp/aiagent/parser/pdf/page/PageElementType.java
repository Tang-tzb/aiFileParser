package com.aifp.aiagent.parser.pdf.page;

/**
 * 页面元素类型。
 * <p>
 * 阶段 6 起支持 TEXT / PENDING_OCR / IMAGE_REGION 三值；AST 级完整元素体系
 * 由阶段 9 设计，此处刻意保持轻量。
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
    PENDING_OCR("PENDING_OCR", "待OCR占位"),

    /**
     * 视觉区域元素（阶段 6：MIXED 页图片占位/表格候选的区域级溯源元数据，
     * 不承载正文文字；表格结构恢复由阶段 7 消费）
     */
    IMAGE_REGION("IMAGE_REGION", "视觉区域元素");

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
