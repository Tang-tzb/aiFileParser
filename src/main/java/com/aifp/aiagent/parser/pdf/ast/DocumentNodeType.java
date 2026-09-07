package com.aifp.aiagent.parser.pdf.ast;

/**
 * AST 节点类型（阶段 9，对应《PDF解析改造方案》§十三）。
 * <p>
 * 全量 14 值冻结；本阶段填充 DOCUMENT（根标记）+ TITLE/PARAGRAPH/TABLE/
 * TABLE_ROW/TABLE_CELL/KEY_VALUE/IMAGE/STAMP/SIGNATURE，
 * SECTION/LIST/HEADER/FOOTER 仅定义（阶段 10 HeaderFooterDetector 与后续
 * LIST/SECTION 识别接入，本阶段不填充）。
 *
 * @author Tang_tzb
 */
public enum DocumentNodeType {

    /**
     * 文档根标记（DocumentAst 隐含根，页内节点不使用）
     */
    DOCUMENT("DOCUMENT", "文档"),

    /**
     * 标题（字号中位数启发式识别，阶段 9）
     */
    TITLE("TITLE", "标题"),

    /**
     * 章节（预留：阶段 10+ 识别接入）
     */
    SECTION("SECTION", "章节"),

    /**
     * 段落（PDF 原生文字或 OCR 行）
     */
    PARAGRAPH("PARAGRAPH", "段落"),

    /**
     * 表格（阶段 7 TableGrid 结构恢复产物）
     */
    TABLE("TABLE", "表格"),

    /**
     * 表格行（TableNode 的行视图）
     */
    TABLE_ROW("TABLE_ROW", "表格行"),

    /**
     * 表格单元格（含表头-值绑定语义）
     */
    TABLE_CELL("TABLE_CELL", "表格单元格"),

    /**
     * 键值对（表格 FUSION 格的语义视图，阶段 9 仅表格来源）
     */
    KEY_VALUE("KEY_VALUE", "键值对"),

    /**
     * 列表（预留：后续识别接入）
     */
    LIST("LIST", "列表"),

    /**
     * 图片区域占位（不承载正文文字）
     */
    IMAGE("IMAGE", "图片"),

    /**
     * 页眉（预留：阶段 10 HeaderFooterDetector 接入）
     */
    HEADER("HEADER", "页眉"),

    /**
     * 页脚（预留：阶段 10 HeaderFooterDetector 接入）
     */
    FOOTER("FOOTER", "页脚"),

    /**
     * 印章占位（红色像素区域，默认不 OCR）
     */
    STAMP("STAMP", "印章"),

    /**
     * 签名/手写占位（低墨迹区域，默认不 OCR）
     */
    SIGNATURE("SIGNATURE", "签名");

    private final String code;
    private final String label;

    DocumentNodeType(String code, String label) {
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
