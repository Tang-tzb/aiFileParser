package com.aifp.aiagent.parser.pdf.page;

import com.aifp.aiagent.parser.pdf.PageContentType;
import com.aifp.aiagent.parser.pdf.PageProfile;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link EmptyPageParser} 单元测试：EMPTY → 无元素空文档。
 *
 * @author Tang_tzb
 */
class EmptyPageParserTest {

    @Test
    void parse_emptyPage_returnsDocumentWithoutElements() {
        EmptyPageParser parser = new EmptyPageParser();

        PageProfile profile = PageProfile.builder()
                .pageNumber(1)
                .contentType(PageContentType.EMPTY)
                .pageWidth(PdfPageTestSupport.PAGE_W)
                .pageHeight(PdfPageTestSupport.PAGE_H)
                .build();
        PageContext context = PageContext.builder()
                .document(new PDDocument())
                .pageIndex(0)
                .profile(profile)
                .build();

        PageDocument result = parser.parse(context);

        assertThat(result.getContentType()).isEqualTo(PageContentType.EMPTY);
        assertThat(result.getParserName()).isEqualTo("EmptyPageParser");
        assertThat(result.getPageNumber()).isEqualTo(1);
        assertThat(result.getElements()).isEmpty();
    }
}
