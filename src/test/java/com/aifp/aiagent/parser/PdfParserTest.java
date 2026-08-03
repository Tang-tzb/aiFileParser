package com.aifp.aiagent.parser;

import com.aifp.aiagent.document.ParserDocument;
import com.aifp.aiagent.entity.enums.FileType;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link PdfParser} 解析测试
 * <p>
 * 用 PDFBox 生成含文本的单页 PDF，再解析验证。
 *
 * @author aiFileParser
 */
class PdfParserTest {

    private static final String EXPECTED_TEXT = "Hello PDFBox Test Project";

    private final PdfParser parser = new PdfParser();

    @Test
    void parse_extractsTextAndMetadata(@TempDir Path tempDir) throws Exception {
        File pdfFile = tempDir.resolve("sample.pdf").toFile();

        // 生成含文本的单页 PDF
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage();
            doc.addPage(page);
            PDType1Font font = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                cs.beginText();
                cs.setFont(font, 12);
                cs.newLineAtOffset(100, 700);
                cs.showText(EXPECTED_TEXT);
                cs.endText();
            }
            doc.save(pdfFile);
        }

        ParserDocument doc = parser.parse(pdfFile);

        assertThat(doc.getContent()).contains("Hello PDFBox Test Project");
        assertThat(doc.getMetadata().getType()).isEqualTo(FileType.PDF);
        assertThat(doc.getMetadata().getPage()).isEqualTo(1);
        assertThat(doc.getMetadata().getFileName()).isEqualTo("sample.pdf");
    }
}
