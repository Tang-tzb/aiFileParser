package com.aifp.aiagent.parser.pdf.ast;

import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * 文档 AST（阶段 9，对应《PDF解析改造方案》§十三）——TEXT_ONLY / IMAGE_ONLY /
 * MIXED 三类 PDF 的统一输出结构。
 * <p>
 * <b>唯一标准结构源（用户约束 3，2026-09-07）</b>：自阶段 9 起，后续 Cleaner（阶段 10）/
 * MarkdownRenderer（阶段 11）/ Chunker（阶段 12）只允许依赖本模型，
 * 禁止再依赖 PageDocument、PDFBox、OCR 等底层解析类型。
 * 解析链路（PdfAnalyzer → PageParserRouter → PageParser）产出的 PageDocument
 * 退化为解析器内部中间产物，不向下游暴露。
 *
 * @author Tang_tzb
 */
@Data
@Builder
public class DocumentAst {

    /**
     * 文档标识（parse 期生成的 UUID；fileId 关联留待生产接线阶段）
     */
    private String documentId;

    /**
     * 源文件名
     */
    private String fileName;

    /**
     * 文档级元数据
     */
    private DocumentAstMetadata metadata;

    /**
     * 页节点列表（页序 = PDF 页序）
     */
    private List<PageNode> pages;
}
