package com.aifp.aiagent.parser.pdf;

import com.aifp.aiagent.common.ResultCode;
import com.aifp.aiagent.exception.BusinessException;
import com.aifp.aiagent.parser.pdf.ast.DocumentAst;
import com.aifp.aiagent.parser.pdf.ast.DocumentAstAssembler;
import com.aifp.aiagent.parser.pdf.page.PageContext;
import com.aifp.aiagent.parser.pdf.page.PageDocument;
import com.aifp.aiagent.parser.pdf.page.PageParserRouter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.springframework.stereotype.Component;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * PDF 文档解析编排器（阶段 9，对应《PDF解析改造方案》§二十五）。
 * <p>
 * 流程：{@link PdfAnalyzer}（逐页画像 + 文档类型聚合）→ {@link PageParserRouter}
 * → {@code PageParser}（阶段 2~8 统一产物 PageDocument）→ {@link DocumentAstAssembler}
 * → {@link DocumentAst}。资源约束：PDDocument try-with-resources 即用即关；
 * 页面解析器内部渲染/OCR 临时文件按既有约定即用即删。
 *
 * @author Tang_tzb
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PdfDocumentParser implements DocumentParser {

    private final PdfAnalyzer pdfAnalyzer;
    private final PageParserRouter pageParserRouter;
    private final DocumentAstAssembler documentAstAssembler;

    @Override
    public DocumentAst parse(File file) {
        try (PDDocument document = Loader.loadPDF(file)) {
            PdfInspectionResult inspection = pdfAnalyzer.analyze(document);
            List<PageDocument> pageDocuments = parsePages(document, inspection);
            DocumentAst ast = documentAstAssembler.assemble(file.getName(), inspection, pageDocuments);
            log.info("PDF 文档 AST 解析完成 file={}, pages={}, documentType={}, 节点统计见各页",
                    file.getName(), ast.getPages().size(), ast.getMetadata().getDocumentType());
            return ast;
        } catch (IOException e) {
            log.error("PDF 解析失败: {}", file.getName(), e);
            throw new BusinessException(ResultCode.FILE_PARSE_ERROR, "PDF 解析失败: " + file.getName());
        }
    }

    /**
     * 逐页路由解析（页序 = PDF 页序；OCR FAILED 等业务异常由解析器抛出传播）。
     */
    private List<PageDocument> parsePages(PDDocument document, PdfInspectionResult inspection) {
        List<PageDocument> pageDocuments = new ArrayList<>(document.getNumberOfPages());
        for (int i = 0; i < document.getNumberOfPages(); i++) {
            PageContext context = PageContext.builder()
                    .document(document)
                    .pageIndex(i)
                    .profile(inspection.getPages().get(i))
                    .build();
            pageDocuments.add(pageParserRouter.route(context).parse(context));
        }
        return pageDocuments;
    }
}
