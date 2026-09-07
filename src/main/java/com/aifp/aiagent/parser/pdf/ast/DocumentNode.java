package com.aifp.aiagent.parser.pdf.ast;

import com.aifp.aiagent.parser.pdf.page.ElementSource;
import com.aifp.aiagent.parser.pdf.text.BoundingBox;
import lombok.Getter;

/**
 * AST 通用节点基类（阶段 9，对应《PDF解析改造方案》§十三/§十四）。
 * <p>
 * 具体类（非抽象）：TITLE/SECTION/PARAGRAPH/IMAGE/HEADER/FOOTER/STAMP/SIGNATURE
 * 等无专属结构的类型直接实例化本类；带专属字段的类型（标题/段落/键值/表格三级）
 * 以子类固定 type 扩展。
 * <p>
 * source/confidence 语义（§十四）：PDF_TEXT 恒 1.0；OCR 为识别置信度归一 0~1；
 * 占位节点（IMAGE/STAMP/SIGNATURE）source=IMAGE、confidence=null。
 * <p>
 * bbox 为 PDF 用户空间（与 BoundingBox 全局约定一致），占位/结构节点可为 null。
 *
 * @author Tang_tzb
 */
@Getter
public class DocumentNode {

    /**
     * 节点类型（子类构造时固定，通用占位节点由构造方指定）
     */
    private final DocumentNodeType type;

    /**
     * 数据来源（PDF_TEXT / OCR / IMAGE / FUSION，§十四）
     */
    private final ElementSource source;

    /**
     * 置信度（0~1；占位节点为 null）
     */
    private final Float confidence;

    /**
     * 外接矩形（PDF 用户空间；可 null，阅读序排序时空值殿后）
     */
    private final BoundingBox bbox;

    /**
     * 说明信息（占位节点使用，如区域描述）
     */
    private final String description;

    public DocumentNode(DocumentNodeType type, ElementSource source, Float confidence,
                        BoundingBox bbox, String description) {
        this.type = type;
        this.source = source;
        this.confidence = confidence;
        this.bbox = bbox;
        this.description = description;
    }
}
