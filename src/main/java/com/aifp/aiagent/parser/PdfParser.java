package com.aifp.aiagent.parser;

import com.aifp.aiagent.common.ResultCode;
import com.aifp.aiagent.document.ParserDocument;
import com.aifp.aiagent.document.ParserDocumentMetadata;
import com.aifp.aiagent.entity.enums.FileType;
import com.aifp.aiagent.exception.BusinessException;
import com.aifp.aiagent.parser.pdf.DocumentParser;
import com.aifp.aiagent.parser.pdf.ast.DocumentAst;
import com.aifp.aiagent.parser.pdf.clean.DocumentCleaner;
import com.aifp.aiagent.parser.pdf.text.PdfTextExtractor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.springframework.stereotype.Component;

import java.io.File;
import java.io.IOException;

/**
 * PDF 解析器（Apache PDFBox）
 * <p>
 * 双契约（阶段 12 链路替换）：
 * <ul>
 *   <li>{@link #parse(File)}：全文 {@code ParserDocument} 旧契约，服务
 *       非 PDF 结构链路保留的 DocumentChunker 滑窗切片（逐页
 *       {@code PdfText.toPlainText()} 以 \n 拼接）；</li>
 *   <li>{@link #parseStructured(File)}：清洗后 {@link DocumentAst} 结构契约
 *       （{@link DocumentParser} 全链路 → 阶段 10 清洗流水线），服务
 *       HybridSemanticChunker 混合语义切片。</li>
 * </ul>
 * 注意：扫描版 PDF（图片型）文字层为空，由结构链路 OCR 接管。
 *
 * @author Tang_tzb
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PdfParser implements FileParser, StructuredFileParser {

    private final PdfTextExtractor textExtractor;

    private final DocumentParser documentParser;

    private final DocumentCleaner documentCleaner;

    @Override
    public FileType supportedType() {
        return FileType.PDF;
    }

    @Override
    public ParserDocument parse(File file) {
        try (PDDocument pd = Loader.loadPDF(file)) {
            String content = extractContent(pd);

            ParserDocumentMetadata metadata = ParserDocumentMetadata.builder()
                    .fileName(file.getName())
                    .page(pd.getNumberOfPages())
                    .type(FileType.PDF)
                    .build();

            ParserDocument doc = new ParserDocument();
            doc.setContent(content);
            doc.setMetadata(metadata);
            log.info("PDF 解析完成 file={}, pages={}, contentLen={}",
                    file.getName(), pd.getNumberOfPages(), content.length());
            return doc;
        } catch (IOException e) {
            log.error("PDF 解析失败: {}", file.getName(), e);
            throw new BusinessException(ResultCode.FILE_PARSE_ERROR, "PDF 解析失败: " + file.getName());
        }
    }

    /**
     * 结构化解析（阶段 12）：DocumentParser 全链路 → 阶段 10 清洗流水线，
     * 输出清洗后 AST 供混合语义切片。
     */
    @Override
    public DocumentAst parseStructured(File file) {
        DocumentAst ast = documentParser.parse(file);
        log.info("PDF 结构化解析完成 file={}, pages={}",
                file.getName(), ast == null || ast.getPages() == null ? 0 : ast.getPages().size());
        return documentCleaner.clean(ast);
    }

    /**
     * 逐页结构化提取并拼接全文（页与页之间 \n 分隔）。
     */
    private String extractContent(PDDocument pd) {
        StringBuilder content = new StringBuilder();
        for (int i = 0; i < pd.getNumberOfPages(); i++) {
            if (i > 0) {
                content.append('\n');
            }
            content.append(textExtractor.extract(pd, i).toPlainText());
        }
        return content.toString();
    }
}
