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
 * {@link MixedPageParser} 骨架测试：真实"图+文"页 → 文字层 + 待融合占位。
 *
 * @author Tang_tzb
 */
class MixedPageParserTest {

    @TempDir
    Path tempDir;

    @Test
    void parse_mixedPage_returnsTextElementAndPendingElement() throws Exception {
        File pdf = tempDir.resolve("mixed-page.pdf").toFile();
        PdfPageTestSupport.buildMixedPagePdf(pdf);

        MixedPageParser parser = new MixedPageParser(new TextPageParser());
        try (PDDocument doc = Loader.loadPDF(pdf)) {
            PageDocument result = parser.parse(context(doc));

            assertThat(result.getContentType()).isEqualTo(PageContentType.MIXED);
            assertThat(result.getParserName()).isEqualTo("MixedPageParser");
            assertThat(result.getPageNumber()).isEqualTo(1);

            // 骨架：1 个 PDF 文字层元素 + 1 个待融合占位元素
            assertThat(result.getElements()).hasSize(2);
            PageElement textElement = result.getElements().get(0);
            assertThat(textElement.getType()).isEqualTo(PageElementType.TEXT);
            assertThat(textElement.getSource()).isEqualTo(ElementSource.PDF_TEXT);
            assertThat(textElement.getText()).contains("Hello PDFBox page parser fixture");

            PageElement pending = result.getElements().get(1);
            assertThat(pending.getType()).isEqualTo(PageElementType.PENDING_OCR);
            assertThat(pending.getSource()).isEqualTo(ElementSource.IMAGE);
            assertThat(pending.getText()).isEmpty();
            assertThat(pending.getDescription()).contains("1 处图片区域");
        }
    }

    private PageContext context(PDDocument doc) {
        PageProfile profile = PageProfile.builder()
                .pageNumber(1)
                .contentType(PageContentType.MIXED)
                .pageWidth(PdfPageTestSupport.PAGE_W)
                .pageHeight(PdfPageTestSupport.PAGE_H)
                .imageCount(1)
                .hasFullPageImage(true)
                .textCount(30)
                .build();
        return PageContext.builder().document(doc).pageIndex(0).profile(profile).build();
    }
}
