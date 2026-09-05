package com.aifp.aiagent.parser.pdf;

import org.apache.pdfbox.pdmodel.PDDocument;

/**
 * 页面级内容分析器
 * <p>
 * 以 Page 为最小检测单位，分析单页的文字层与图片分布，输出 {@link PageProfile}。
 * 接收 {@link PDDocument} + 页索引：因 PDFTextStripper 为文档级 API（按起止页抽取），
 * 图片统计经 {@code document.getPage(pageIndex)} 取页。
 *
 * @author Tang_tzb
 */
public interface PageAnalyzer {

    /**
     * 分析指定页。
     *
     * @param document  已加载的 PDF 文档
     * @param pageIndex 页索引（0-based）
     * @return 页面画像（含内容类型判定）
     */
    PageProfile analyze(PDDocument document, int pageIndex);
}
