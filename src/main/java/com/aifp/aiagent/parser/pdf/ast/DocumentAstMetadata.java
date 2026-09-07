package com.aifp.aiagent.parser.pdf.ast;

import com.aifp.aiagent.parser.pdf.PdfContentType;
import lombok.Builder;
import lombok.Data;

/**
 * 文档级元数据（阶段 9）。
 *
 * @author Tang_tzb
 */
@Data
@Builder
public class DocumentAstMetadata {

    /**
     * 总页数
     */
    private int totalPages;

    /**
     * 文档级内容类型（阶段 1 PdfAnalyzer 聚合结果）
     */
    private PdfContentType documentType;
}
