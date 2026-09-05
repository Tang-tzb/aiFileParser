package com.aifp.aiagent.parser.pdf;

import org.apache.pdfbox.pdmodel.PDDocument;

/**
 * PDF 文档级内容分析器
 * <p>
 * 遍历全部页面调用 {@link PageAnalyzer}，聚合为 {@link PdfInspectionResult}。
 * 只负责"判断每页是什么类型"，不解析内容（解析由阶段 2 PageParserRouter 接管）。
 *
 * @author Tang_tzb
 */
public interface PdfAnalyzer {

    /**
     * 分析整个 PDF 文档。
     *
     * @param document 已加载的 PDF 文档
     * @return 检测结果（逐页画像 + 文档级聚合类型）
     */
    PdfInspectionResult analyze(PDDocument document);
}
