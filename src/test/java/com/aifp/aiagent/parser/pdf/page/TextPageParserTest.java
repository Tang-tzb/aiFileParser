package com.aifp.aiagent.parser.pdf.page;

import com.aifp.aiagent.parser.pdf.PageContentType;
import com.aifp.aiagent.parser.pdf.PageProfile;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link TextPageParser} 单元测试：真实文字页 → TEXT 元素。
 *
 * @author Tang_tzb
 */
class TextPageParserTest {

    @TempDir
    Path tempDir;

    @Test
    void parse_textPage_returnsSinglePdfTextElement() throws Exception {
        File pdf = tempDir.resolve("text-page.pdf").toFile();
        PdfPageTestSupport.buildTextPagePdf(pdf);

        TextPageParser parser = PdfPageTestSupport.buildTextPageParser();
        try (PDDocument doc = Loader.loadPDF(pdf)) {
            PageDocument result = parser.parse(context(doc));

            assertThat(result.getContentType()).isEqualTo(PageContentType.TEXT_ONLY);
            assertThat(result.getParserName()).isEqualTo("TextPageParser");
            assertThat(result.getPageNumber()).isEqualTo(1);
            assertThat(result.getPageWidth()).isGreaterThan(0);
            assertThat(result.getPageHeight()).isGreaterThan(0);

            assertThat(result.getElements()).hasSize(1);
            PageElement element = result.getElements().get(0);
            assertThat(element.getType()).isEqualTo(PageElementType.TEXT);
            assertThat(element.getSource()).isEqualTo(ElementSource.PDF_TEXT);
            assertThat(element.getText()).contains("Hello PDFBox page parser fixture");
            // 阶段 3 结构化字段：TextBlock → BoundingBox 链
            assertThat(element.getBbox()).isNotNull();
            assertThat(element.getBbox().getX()).isGreaterThan(0);
            assertThat(element.getBbox().top()).isLessThan(PdfPageTestSupport.PAGE_H + 1);
            assertThat(element.getFontSize()).isNotNull().isGreaterThan(0);
            assertThat(element.getFontName()).isNotBlank();
        }
    }

    private PageContext context(PDDocument doc) {
        PageProfile profile = PageProfile.builder()
                .pageNumber(1)
                .contentType(PageContentType.TEXT_ONLY)
                .pageWidth(PdfPageTestSupport.PAGE_W)
                .pageHeight(PdfPageTestSupport.PAGE_H)
                .textCount(30)
                .build();
        return PageContext.builder().document(doc).pageIndex(0).profile(profile).build();
    }
}
