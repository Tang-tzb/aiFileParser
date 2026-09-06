package com.aifp.aiagent.parser.pdf.region;

/**
 * 视觉区域类型（六型，阶段 6 冻结，不随意新增）。
 * <p>
 * 分类对象为 PDF 图片占位区域与原生文字网格候选；
 * TEXT 区域由 TextBlock 承载（不产独立区域对象），枚举值保留语义完整性。
 *
 * @author Tang_tzb
 */
public enum RegionType {

    /**
     * 文字区域：PDF 原生文字覆盖（不参与 OCR）
     */
    TEXT("TEXT", "文字区域"),

    /**
     * 表格区域候选：原生文字网格对齐命中（likelyTable=true + tableScore）。
     * 阶段 6 仅候选标记，表格线检测与 Cell 恢复由阶段 7 完成
     */
    TABLE("TABLE", "表格区域候选"),

    /**
     * 普通图片区域（照片/扫描图；默认不进入正文 OCR）
     */
    IMAGE("IMAGE", "图片区域"),

    /**
     * 印章区域（红色像素特征命中；默认不 OCR）
     */
    STAMP("STAMP", "印章区域"),

    /**
     * 签名/手写区域（低墨迹 + 页面下半部；默认不 OCR）
     */
    SIGNATURE("SIGNATURE", "签名区域"),

    /**
     * 未知区域（像素信息不可用兜底；由评估器综合判断）
     */
    UNKNOWN("UNKNOWN", "未知区域");

    private final String code;
    private final String label;

    RegionType(String code, String label) {
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
