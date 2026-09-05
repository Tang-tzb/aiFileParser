package com.aifp.aiagent.parser.pdf.page;

import com.aifp.aiagent.parser.pdf.PageContentType;
import com.aifp.aiagent.parser.pdf.PageProfile;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link ImagePageParser} 单元测试：IMAGE_ONLY → 明确的待 OCR 占位。
 *
 * @author Tang_tzb
 */
class ImagePageParserTest {

    @Test
    void parse_imageOnlyPage_returnsPendingOcrPlaceholder() {
        ImagePageParser parser = new ImagePageParser();

        PageProfile profile = PageProfile.builder()
                .pageNumber(1)
                .contentType(PageContentType.IMAGE_ONLY)
                .pageWidth(PdfPageTestSupport.PAGE_W)
                .pageHeight(PdfPageTestSupport.PAGE_H)
                .imageCount(1)
                .hasFullPageImage(true)
                .build();
        // IMAGE_ONLY 解析不读取文档内容，空文档即可
        PageContext context = PageContext.builder()
                .document(new PDDocument())
                .pageIndex(0)
                .profile(profile)
                .build();

        PageDocument result = parser.parse(context);

        assertThat(result.getContentType()).isEqualTo(PageContentType.IMAGE_ONLY);
        assertThat(result.getParserName()).isEqualTo("ImagePageParser");
        assertThat(result.getPageWidth()).isGreaterThan(0);
        assertThat(result.getPageHeight()).isGreaterThan(0);

        assertThat(result.getElements()).hasSize(1);
        PageElement element = result.getElements().get(0);
        assertThat(element.getType()).isEqualTo(PageElementType.PENDING_OCR);
        assertThat(element.getSource()).isEqualTo(ElementSource.IMAGE);
        assertThat(element.getText()).isEmpty();
        assertThat(element.getDescription()).isNotBlank();
    }
}
