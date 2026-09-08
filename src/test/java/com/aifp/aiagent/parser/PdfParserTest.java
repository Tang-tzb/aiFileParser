package com.aifp.aiagent.parser;

import com.aifp.aiagent.document.ParserDocument;
import com.aifp.aiagent.entity.enums.FileType;
import com.aifp.aiagent.parser.pdf.DocumentParser;
import com.aifp.aiagent.parser.pdf.ast.DocumentAst;
import com.aifp.aiagent.parser.pdf.clean.DocumentCleaner;
import com.aifp.aiagent.parser.pdf.text.PdfTextExtractor;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.InOrder;

import java.io.File;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

/**
 * {@link PdfParser} 解析测试
 * <p>
 * 用 PDFBox 生成含文本的单页 PDF，再解析验证。
 *
 * @author Tang_tzb
 */
class PdfParserTest {

    private static final String EXPECTED_TEXT = "Hello PDFBox Test Project";

    private final PdfParser parser = new PdfParser(
            new com.aifp.aiagent.parser.pdf.text.DefaultPdfTextExtractor(
                    new com.aifp.aiagent.parser.pdf.text.PdfCoordinateConverter()),
            // 本测试仅覆盖 parse(File) 全文旧契约，结构链路依赖以 mock 占位
            org.mockito.Mockito.mock(com.aifp.aiagent.parser.pdf.DocumentParser.class),
            org.mockito.Mockito.mock(com.aifp.aiagent.parser.pdf.clean.DocumentCleaner.class));

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

    /**
     * 阶段 13 链路固化：parseStructured = DocumentParser.parse → DocumentCleaner.clean，
     * 顺序固定且返回清洗后 AST（ingest 上游 parse/clean 环节的契约防线）。
     */
    @Test
    void parseStructured_runsParseThenClean() {
        DocumentParser documentParser = mock(DocumentParser.class);
        DocumentCleaner documentCleaner = mock(DocumentCleaner.class);
        PdfParser structuredParser = new PdfParser(
                mock(PdfTextExtractor.class), documentParser, documentCleaner);

        File pdfFile = new File("sample.pdf");
        DocumentAst raw = DocumentAst.builder()
                .documentId("doc-1").fileName("sample.pdf").pages(List.of()).build();
        DocumentAst cleaned = DocumentAst.builder()
                .documentId("doc-1").fileName("sample.pdf").pages(List.of()).build();
        when(documentParser.parse(pdfFile)).thenReturn(raw);
        when(documentCleaner.clean(raw)).thenReturn(cleaned);

        DocumentAst result = structuredParser.parseStructured(pdfFile);

        assertThat(result).isSameAs(cleaned);
        InOrder inOrder = inOrder(documentParser, documentCleaner);
        inOrder.verify(documentParser).parse(pdfFile);
        inOrder.verify(documentCleaner).clean(raw);
    }
}
