package com.aifp.aiagent.parser.pdf.ast;

import com.aifp.aiagent.parser.pdf.page.ElementSource;
import com.aifp.aiagent.parser.pdf.text.BoundingBox;
import lombok.Getter;

/**
 * 键值对节点（阶段 9，仅表格 FUSION 格来源，用户确认）。
 * <p>
 * 语义视图定位：<b>不进入 {@link PageNode#getNodes()} 阅读序</b>，存于
 * {@link PageNode#getKeyValues()} 独立列表——阶段 11 MarkdownRenderer 只渲染
 * nodes（表格已含表头列与值列），结构性杜绝键值重复输出（约束 2）；
 * 阶段 12 Chunker 经 keyValues 按 §二十一优先级单独成块。
 * <p>
 * 来源约定（§十四）：节点整体 source=FUSION；key 源自表头 OCR（keySource=OCR），
 * value 源自 PDF 原生文字（valueSource=PDF_TEXT），confidence 透传绑定单元格。
 *
 * @author Tang_tzb
 */
@Getter
public class KeyValueNode extends DocumentNode {

    /**
     * 键（表头文字，OCR 来源）
     */
    private final String key;

    /**
     * 值（单元格值，PDF 原生文字，多行以 \n 连接）
     */
    private final String value;

    /**
     * 键的数据来源（恒 OCR）
     */
    private final ElementSource keySource;

    /**
     * 值的数据来源（恒 PDF_TEXT）
     */
    private final ElementSource valueSource;

    public KeyValueNode(String key, String value, ElementSource keySource,
                        ElementSource valueSource, Float confidence, BoundingBox bbox) {
        super(DocumentNodeType.KEY_VALUE, ElementSource.FUSION, confidence, bbox, null);
        this.key = key;
        this.value = value;
        this.keySource = keySource;
        this.valueSource = valueSource;
    }
}
