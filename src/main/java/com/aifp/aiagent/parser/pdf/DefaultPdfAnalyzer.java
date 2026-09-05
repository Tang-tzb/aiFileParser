package com.aifp.aiagent.parser.pdf;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 默认 PDF 文档级内容分析器
 * <p>
 * 遍历全部页面逐页调用 {@link PageAnalyzer}，并按以下规则聚合文档级类型：
 * <ul>
 *   <li>非 EMPTY 页类型唯一 → 该类型；</li>
 *   <li>非 EMPTY 页存在多种类型 → MIXED；</li>
 *   <li>全部页 EMPTY（或无页面）→ EMPTY。</li>
 * </ul>
 *
 * @author Tang_tzb
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DefaultPdfAnalyzer implements PdfAnalyzer {

    private final PageAnalyzer pageAnalyzer;

    @Override
    public PdfInspectionResult analyze(PDDocument document) {
        int totalPages = document.getNumberOfPages();
        List<PageProfile> profiles = new ArrayList<>(totalPages);
        for (int i = 0; i < totalPages; i++) {
            profiles.add(pageAnalyzer.analyze(document, i));
        }
        PdfContentType documentType = aggregate(profiles);
        log.info("PDF 内容检测完成 totalPages={}, documentType={}, 页类型=[{}]",
                totalPages, documentType, summarize(profiles));
        return PdfInspectionResult.builder()
                .totalPages(totalPages)
                .pages(profiles)
                .documentType(documentType)
                .build();
    }

    /**
     * 文档级类型聚合：EMPTY 页不参与聚合。
     */
    private PdfContentType aggregate(List<PageProfile> profiles) {
        Set<PageContentType> nonEmpty = profiles.stream()
                .map(PageProfile::getContentType)
                .filter(type -> type != PageContentType.EMPTY)
                .collect(Collectors.toSet());
        if (nonEmpty.isEmpty()) {
            return PdfContentType.EMPTY;
        }
        if (nonEmpty.size() == 1) {
            // 页级与文档级枚举常量同名，直接按名映射
            return PdfContentType.valueOf(nonEmpty.iterator().next().name());
        }
        return PdfContentType.MIXED;
    }

    /**
     * 生成"页码:类型"摘要用于日志。
     */
    private String summarize(List<PageProfile> profiles) {
        return profiles.stream()
                .map(p -> p.getPageNumber() + ":" + p.getContentType().getCode())
                .collect(Collectors.joining(", "));
    }
}
