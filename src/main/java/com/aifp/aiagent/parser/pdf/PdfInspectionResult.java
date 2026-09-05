package com.aifp.aiagent.parser.pdf;

import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * PDF 文档级内容检测结果
 * <p>
 * 聚合全部页面的 {@link PageProfile}，并给出文档级类型。
 * 后续阶段（PageParserRouter / DocumentParser）以本结果为路由依据。
 *
 * @author Tang_tzb
 */
@Data
@Builder
public class PdfInspectionResult {

    /**
     * 总页数
     */
    private int totalPages;

    /**
     * 逐页画像（顺序与文档页序一致）
     */
    private List<PageProfile> pages;

    /**
     * 文档级聚合类型
     */
    private PdfContentType documentType;
}
