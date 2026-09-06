package com.aifp.aiagent.parser.pdf.text;

import org.apache.pdfbox.pdmodel.PDDocument;

/**
 * PDF 结构化文字提取接口：PDF → TextPosition → TextBlock（阶段 3 目标链路）。
 * <p>
 * 以 Page 为单位输出 {@link PdfText}（块级结构 + 行级明细 + 坐标），
 * 取代"整页拍平 String"；旧全文能力经 {@link PdfText#toPlainText()} 保留。
 *
 * @author Tang_tzb
 */
public interface PdfTextExtractor {

    /**
     * 提取指定页的结构化文字。
     *
     * @param document  已加载的 PDF 文档
     * @param pageIndex 页索引（0-based）
     * @return 页级结构化文字
     */
    PdfText extract(PDDocument document, int pageIndex);
}
