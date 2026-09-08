package com.aifp.aiagent.parser.pdf.chunk;

/**
 * 切片块类型（阶段 12，对应《PDF解析改造方案》阶段 12）。
 * <p>
 * 与切片优先级链（标题 &gt; 章节 &gt; 段落 &gt; Table &gt; KeyValue &gt; List
 * &gt; Sentence &gt; Token Length）对应：结构层产出 {@link #PARAGRAPH} /
 * {@link #TABLE} / {@link #KEY_VALUE}，超长降级层产出 {@link #SENTENCE} /
 * {@link #TOKEN}；{@link #LIST} 为预留位（DocumentNodeType.LIST 当前链路
 * 不产出，见阶段 12 计划决策 D9）。
 *
 * @author Tang_tzb
 */
public enum ChunkType {

    /**
     * 标题 + 段落聚合块（段落完整，不截断句子）
     */
    PARAGRAPH("PARAGRAPH", "段落"),

    /**
     * 表格块（完整表格单块，或大表格"表头 + N 行"行组块，每组重复表头）
     */
    TABLE("TABLE", "表格"),

    /**
     * 键值清单块（完整 key：value 对，绝不拆散单对）
     */
    KEY_VALUE("KEY_VALUE", "键值"),

    /**
     * 列表块（预留：LIST 识别接入后启用）
     */
    LIST("LIST", "列表"),

    /**
     * 句子降级块（超长段落按句子边界切分）
     */
    SENTENCE("SENTENCE", "句子"),

    /**
     * token 窗口降级块（单句仍超预算时的最后兜底，overlap 仅此层生效）
     */
    TOKEN("TOKEN", "词元");

    private final String code;

    private final String label;

    ChunkType(String code, String label) {
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
