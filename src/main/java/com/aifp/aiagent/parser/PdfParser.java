package com.aifp.aiagent.parser;

import com.aifp.aiagent.common.ResultCode;
import com.aifp.aiagent.document.ParserDocument;
import com.aifp.aiagent.document.ParserDocumentMetadata;
import com.aifp.aiagent.entity.enums.FileType;
import com.aifp.aiagent.exception.BusinessException;
import lombok.extern.slf4j.Slf4j;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.stereotype.Component;

import java.io.File;
import java.io.IOException;

/**
 * PDF 解析器（Apache PDFBox）
 * <p>
 * 使用 {@link PDFTextStripper} 抽取文本，{@code page=文档页数}。
 * 注意：扫描版 PDF（图片型）抽取结果为空，后续可由 OCR 接管，本阶段不处理。
 *
 * @author aiFileParser
 */
@Slf4j
@Component
public class PdfParser implements FileParser {

    @Override
    public FileType supportedType() {
        return FileType.PDF;
    }

    @Override
    public ParserDocument parse(File file) {
        try (PDDocument pd = Loader.loadPDF(file)) {
            PDFTextStripper stripper = new PDFTextStripper();
            String content = stripper.getText(pd);

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
}
