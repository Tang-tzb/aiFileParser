package com.aifp.aiagent.parser;

import com.aifp.aiagent.common.ResultCode;
import com.aifp.aiagent.document.ParserDocument;
import com.aifp.aiagent.document.ParserDocumentMetadata;
import com.aifp.aiagent.entity.enums.FileType;
import com.aifp.aiagent.exception.BusinessException;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.springframework.stereotype.Component;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;

/**
 * Word 解析器（Apache POI XWPF）
 * <p>
 * 仅支持 {@code .docx}（XWPF），遍历段落抽取文本，{@code page=段落数}。
 * 旧版 {@code .doc} 不支持，抛 {@code FILE_PARSE_ERROR}。
 *
 * @author aiFileParser
 */
@Slf4j
@Component
public class WordParser implements FileParser {

    @Override
    public FileType supportedType() {
        return FileType.WORD;
    }

    @Override
    public ParserDocument parse(File file) {
        if (file.getName().toLowerCase().endsWith(".doc")) {
            throw new BusinessException(ResultCode.FILE_PARSE_ERROR,
                    "暂不支持.doc旧版格式，请转换为.docx: " + file.getName());
        }
        try (FileInputStream fis = new FileInputStream(file);
             XWPFDocument docx = new XWPFDocument(fis)) {

            StringBuilder sb = new StringBuilder();
            int paragraphCount = 0;
            for (XWPFParagraph p : docx.getParagraphs()) {
                String text = p.getText();
                if (text != null && !text.isBlank()) {
                    sb.append(text).append("\n");
                    paragraphCount++;
                }
            }

            ParserDocumentMetadata metadata = ParserDocumentMetadata.builder()
                    .fileName(file.getName())
                    .page(paragraphCount)
                    .type(FileType.WORD)
                    .build();

            ParserDocument doc = new ParserDocument();
            doc.setContent(sb.toString());
            doc.setMetadata(metadata);
            log.info("Word 解析完成 file={}, paragraphs={}, contentLen={}",
                    file.getName(), paragraphCount, sb.length());
            return doc;
        } catch (IOException e) {
            log.error("Word 解析失败: {}", file.getName(), e);
            throw new BusinessException(ResultCode.FILE_PARSE_ERROR, "Word 解析失败: " + file.getName());
        }
    }
}
