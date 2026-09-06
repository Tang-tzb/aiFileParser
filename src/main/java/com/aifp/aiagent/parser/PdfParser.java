package com.aifp.aiagent.parser;

import com.aifp.aiagent.common.ResultCode;
import com.aifp.aiagent.document.ParserDocument;
import com.aifp.aiagent.document.ParserDocumentMetadata;
import com.aifp.aiagent.entity.enums.FileType;
import com.aifp.aiagent.exception.BusinessException;
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
 * 阶段 3 起，全文由结构化文字提取器生成：逐页 {@code PdfText.toPlainText()}
 * 以 \n 拼接，保持 ParserDocument.content 旧契约不变（供 DocumentChunker 使用）。
 * 注意：扫描版 PDF（图片型）文字层为空，由后续阶段 OCR 接管，本类不处理。
 *
 * @author Tang_tzb
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PdfParser implements FileParser {

    private final PdfTextExtractor textExtractor;

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
