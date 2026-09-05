package com.aifp.aiagent.parser.pdf.page;

import com.aifp.aiagent.exception.BusinessException;
import com.aifp.aiagent.parser.pdf.PageProfile;
import com.aifp.aiagent.parser.pdf.PdfInspectionResult;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link PageParserRouter} 路由验收测试（阶段 2 核心验收）：
 * TEXT_ONLY→TextPageParser、IMAGE_ONLY→ImagePageParser、
 * MIXED→MixedPageParser、EMPTY→EmptyPageParser，
 * 并验证 route() 为查表实现（无类型分支）。
 *
 * @author Tang_tzb
 */
class PageParserRouterTest {

    @TempDir
    Path tempDir;

    @Test
    void route_fourPageTypes_toMatchingParsers() throws Exception {
        File pdf = tempDir.resolve("four-pages.pdf").toFile();
        PdfPageTestSupport.buildFourPagePdf(pdf);

        try (PDDocument doc = Loader.loadPDF(pdf)) {
            // 阶段 1 检测层实测每页类型，作为路由输入
            PdfInspectionResult inspection = PdfPageTestSupport.buildAnalyzer().analyze(doc);
            PageParserRouter router = PdfPageTestSupport.buildRouter();

            // 验收点 1：TEXT_ONLY → TextPageParser
            assertRoutedTo(router, doc, inspection.getPages().get(0), 0, TextPageParser.class);
            // 验收点 2：IMAGE_ONLY → ImagePageParser
            assertRoutedTo(router, doc, inspection.getPages().get(1), 1, ImagePageParser.class);
            // 验收点 3：MIXED → MixedPageParser
            assertRoutedTo(router, doc, inspection.getPages().get(2), 2, MixedPageParser.class);
            // 验收点 4：EMPTY → EmptyPageParser
            assertRoutedTo(router, doc, inspection.getPages().get(3), 3, EmptyPageParser.class);
        }
    }

    @Test
    void route_endToEnd_parserNameMatchesRoutedParser() throws Exception {
        File pdf = tempDir.resolve("end-to-end.pdf").toFile();
        PdfPageTestSupport.buildFourPagePdf(pdf);

        try (PDDocument doc = Loader.loadPDF(pdf)) {
            PdfInspectionResult inspection = PdfPageTestSupport.buildAnalyzer().analyze(doc);
            PageParserRouter router = PdfPageTestSupport.buildRouter();

            PageDocument textDoc = parseByRoute(router, doc, inspection.getPages().get(0), 0);
            assertThat(textDoc.getParserName()).isEqualTo("TextPageParser");
            assertThat(textDoc.getContentType())
                    .isEqualTo(com.aifp.aiagent.parser.pdf.PageContentType.TEXT_ONLY);

            PageDocument imageDoc = parseByRoute(router, doc, inspection.getPages().get(1), 1);
            assertThat(imageDoc.getParserName()).isEqualTo("ImagePageParser");

            PageDocument mixedDoc = parseByRoute(router, doc, inspection.getPages().get(2), 2);
            assertThat(mixedDoc.getParserName()).isEqualTo("MixedPageParser");

            PageDocument emptyDoc = parseByRoute(router, doc, inspection.getPages().get(3), 3);
            assertThat(emptyDoc.getParserName()).isEqualTo("EmptyPageParser");
            assertThat(emptyDoc.getElements()).isEmpty();
        }
    }

    @Test
    void route_unregisteredType_throwsBusinessException() {
        PageProfile profile = PageProfile.builder()
                .pageNumber(1)
                .contentType(com.aifp.aiagent.parser.pdf.PageContentType.MIXED)
                .build();
        PageContext context = PageContext.builder().pageIndex(0).profile(profile).build();

        // 未注册任何解析器的路由器：查表落空，按约定抛业务异常
        PageParserRouter emptyRouter = new PageParserRouter(java.util.List.of());
        assertThatThrownBy(() -> emptyRouter.route(context))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("未注册");
    }

    private void assertRoutedTo(PageParserRouter router, PDDocument doc,
                                PageProfile profile, int pageIndex, Class<?> expected) {
        PageParser parser = router.route(buildContext(doc, profile, pageIndex));
        assertThat(parser).as("第%d页路由结果", pageIndex + 1).isInstanceOf(expected);
    }

    private PageDocument parseByRoute(PageParserRouter router, PDDocument doc,
                                      PageProfile profile, int pageIndex) {
        return router.route(buildContext(doc, profile, pageIndex)).parse(buildContext(doc, profile, pageIndex));
    }

    private PageContext buildContext(PDDocument doc, PageProfile profile, int pageIndex) {
        return PageContext.builder()
                .document(doc)
                .pageIndex(pageIndex)
                .profile(profile)
                .build();
    }
}
