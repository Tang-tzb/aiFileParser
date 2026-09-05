package com.aifp.aiagent.parser.pdf;

/**
 * 页面内容类型
 * <p>
 * 以 Page 为最小检测单位，由 {@link PageAnalyzer} 依据文字与图片分布判定。
 * 判定规则参考图片占位面积而非简单图片数量，避免 Logo/印章小图干扰。
 *
 * @author Tang_tzb
 */
public enum PageContentType {

    /**
     * 纯文字页：有有效文字层，且无大面积图片（小 Logo 不影响）
     */
    TEXT_ONLY("TEXT_ONLY", "纯文字页"),

    /**
     * 整页图片页：无有效文字层，内容由图片承载（典型：扫描件）
     */
    IMAGE_ONLY("IMAGE_ONLY", "整页图片页"),

    /**
     * 图文混合页：既有有效文字层，又存在大面积图片（典型：图片表格 + 文字数据）
     */
    MIXED("MIXED", "图文混合页"),

    /**
     * 空白页：既无有效文字也无图片
     */
    EMPTY("EMPTY", "空白页");

    private final String code;
    private final String label;

    PageContentType(String code, String label) {
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
