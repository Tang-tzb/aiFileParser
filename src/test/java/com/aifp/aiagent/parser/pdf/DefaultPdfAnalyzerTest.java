package com.aifp.aiagent.parser.pdf;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;

import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link DefaultPdfAnalyzer} 内容检测测试（离线，PDFBox 程序化生成测试 PDF）。
 * <p>
 * 覆盖阶段 1 验收场景：TEXT_ONLY / IMAGE_ONLY / MIXED / EMPTY 判定、
 * Logo 不导致 TEXT_ONLY 误判为 MIXED、跨页独立判定、分类阈值边界。
 *
 * @author Tang_tzb
 */
class DefaultPdfAnalyzerTest {

    private static final float PAGE_W = 595f;
    private static final float PAGE_H = 842f;

    private static DefaultPdfAnalyzer analyzer;

    @BeforeAll
    static void setUp() {
        DefaultPageAnalyzer pageAnalyzer = new DefaultPageAnalyzer();
        // 注入 @Value 字段（不启动 Spring 容器），与生产默认值一致
        ReflectionTestUtils.setField(pageAnalyzer, "fullImageRatio", 0.85);
        ReflectionTestUtils.setField(pageAnalyzer, "largeImageRatio", 0.20);
        ReflectionTestUtils.setField(pageAnalyzer, "textMinCount", 5);
        analyzer = new DefaultPdfAnalyzer(pageAnalyzer);
    }

    @Test
    void analyze_sixPages_eachPageClassifiedCorrectly(@TempDir Path tempDir) throws Exception {
        File pdf = tempDir.resolve("six-pages.pdf").toFile();
        buildSixPagePdf(pdf);

        try (PDDocument doc = Loader.loadPDF(pdf)) {
            PdfInspectionResult result = analyzer.analyze(doc);

            assertThat(result.getTotalPages()).isEqualTo(6);
            assertThat(result.getPages()).hasSize(6);
            // 非空白页存在 TEXT_ONLY / IMAGE_ONLY / MIXED 三种类型 → 文档级 MIXED
            assertThat(result.getDocumentType()).isEqualTo(PdfContentType.MIXED);

            assertPageType(result, 1, PageContentType.TEXT_ONLY);
            assertPageType(result, 2, PageContentType.IMAGE_ONLY);
            assertPageType(result, 3, PageContentType.MIXED);
            // 验收点：文字 + 小 Logo 不误判为 MIXED
            assertPageType(result, 4, PageContentType.TEXT_ONLY);
            // 验收点：空白页识别为 EMPTY
            assertPageType(result, 5, PageContentType.EMPTY);
            assertPageType(result, 6, PageContentType.TEXT_ONLY);
        }
    }

    @Test
    void analyze_profileFieldsConsistentWithPageContent(@TempDir Path tempDir) throws Exception {
        File pdf = tempDir.resolve("fields.pdf").toFile();
        buildSixPagePdf(pdf);

        try (PDDocument doc = Loader.loadPDF(pdf)) {
            PdfInspectionResult result = analyzer.analyze(doc);

            PageProfile p1 = result.getPages().get(0);
            assertThat(p1.getPageNumber()).isEqualTo(1);
            assertThat(p1.getPageWidth()).isGreaterThan(0);
            assertThat(p1.getPageHeight()).isGreaterThan(0);
            assertThat(p1.getTextCount()).isGreaterThan(5);
            assertThat(p1.getImageCount()).isZero();
            assertThat(p1.isHasFullPageImage()).isFalse();
            assertThat(p1.isHasTableLikeRegion()).isFalse();

            PageProfile p2 = result.getPages().get(1);
            assertThat(p2.getImageCount()).isEqualTo(1);
            assertThat(p2.getImageAreaRatio()).isGreaterThan(0.85);
            assertThat(p2.isHasFullPageImage()).isTrue();
            assertThat(p2.getTextCount()).isZero();
        }
    }

    @Test
    void classify_thresholdMatrix() {
        // 空页：无文字无图片
        assertThat(DefaultPageAnalyzer.classify(false, false, false))
                .isEqualTo(PageContentType.EMPTY);
        // 无文字有图片（含大图/整页图）→ IMAGE_ONLY
        assertThat(DefaultPageAnalyzer.classify(false, true, true))
                .isEqualTo(PageContentType.IMAGE_ONLY);
        assertThat(DefaultPageAnalyzer.classify(false, false, true))
                .isEqualTo(PageContentType.IMAGE_ONLY);
        // 文字 + 大图 → MIXED
        assertThat(DefaultPageAnalyzer.classify(true, true, true))
                .isEqualTo(PageContentType.MIXED);
        assertThat(DefaultPageAnalyzer.classify(true, true, false))
                .isEqualTo(PageContentType.MIXED);
        // 文字 + 仅小图（Logo）→ TEXT_ONLY
        assertThat(DefaultPageAnalyzer.classify(true, false, true))
                .isEqualTo(PageContentType.TEXT_ONLY);
        // 纯文字
        assertThat(DefaultPageAnalyzer.classify(true, false, false))
                .isEqualTo(PageContentType.TEXT_ONLY);
    }

    /**
     * 生成 6 页测试 PDF：
     * 1 纯文字 / 2 整页图片 / 3 图片+文字 / 4 文字+小Logo / 5 空白 / 6 纯文字。
     */
    private void buildSixPagePdf(File pdf) throws Exception {
        try (PDDocument doc = new PDDocument()) {
            appendTextPage(doc, "Hello PDFBox Content Detector Page One");
            appendImagePage(doc, false);
            appendImagePage(doc, true);
            appendLogoPage(doc);
            doc.addPage(new PDPage());
            appendTextPage(doc, "Hello PDFBox Content Detector Page Six");
            doc.save(pdf);
        }
    }

    /**
     * 追加一页纯文字（多行，非空白字符数远超 text-min-count）。
     */
    private void appendTextPage(PDDocument doc, String line1) throws Exception {
        PDPage page = new PDPage();
        doc.addPage(page);
        PDType1Font font = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
        try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
            cs.beginText();
            cs.setFont(font, 12);
            cs.newLineAtOffset(60, PAGE_H - 80);
            cs.showText(line1);
            cs.endText();
            cs.beginText();
            cs.setFont(font, 12);
            cs.newLineAtOffset(60, PAGE_H - 120);
            cs.showText("The quick brown fox jumps over the lazy dog");
            cs.endText();
        }
    }

    /**
     * 追加一页整页图片；overlayText 为 true 时在图片上方叠加文字（MIXED 页）。
     */
    private void appendImagePage(PDDocument doc, boolean overlayText) throws Exception {
        PDPage page = new PDPage();
        doc.addPage(page);
        BufferedImage img = buildImage(200, 200);
        try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
            cs.drawImage(LosslessFactory.createFromImage(doc, img), 0, 0, PAGE_W, PAGE_H);
            if (overlayText) {
                PDType1Font font = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
                cs.beginText();
                cs.setFont(font, 12);
                cs.newLineAtOffset(60, PAGE_H - 80);
                cs.showText("Mixed page with text over full page image");
                cs.endText();
            }
        }
    }

    /**
     * 追加一页文字 + 右下角小 Logo（约 3% 页面面积，低于 large-image-ratio）。
     */
    private void appendLogoPage(PDDocument doc) throws Exception {
        PDPage page = new PDPage();
        doc.addPage(page);
        BufferedImage logo = buildImage(80, 80);
        PDType1Font font = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
        try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
            cs.beginText();
            cs.setFont(font, 12);
            cs.newLineAtOffset(60, PAGE_H - 80);
            cs.showText("Text page with a small company logo bottom right");
            cs.endText();
            // Logo 边长 130pt：面积占比 ≈ 130*130/(595*842) ≈ 3.4%
            cs.drawImage(LosslessFactory.createFromImage(doc, logo),
                    PAGE_W - 150, 20, 130, 130);
        }
    }

    /**
     * 构造非纯色测试图片（白底 + 黑块），模拟扫描内容。
     */
    private BufferedImage buildImage(int width, int height) {
        BufferedImage img = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        java.awt.Graphics2D g = img.createGraphics();
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, width, height);
        g.setColor(Color.BLACK);
        g.fillRect(width / 4, height / 4, width / 2, height / 2);
        g.dispose();
        return img;
    }

    private void assertPageType(PdfInspectionResult result, int pageNumber, PageContentType expected) {
        PageProfile profile = result.getPages().get(pageNumber - 1);
        assertThat(profile.getPageNumber()).isEqualTo(pageNumber);
        assertThat(profile.getContentType()).as("第%d页类型", pageNumber).isEqualTo(expected);
    }
}
