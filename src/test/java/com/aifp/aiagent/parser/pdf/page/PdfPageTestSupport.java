package com.aifp.aiagent.parser.pdf.page;

import com.aifp.aiagent.parser.pdf.DefaultPageAnalyzer;
import com.aifp.aiagent.parser.pdf.DefaultPdfAnalyzer;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory;
import org.springframework.test.util.ReflectionTestUtils;

import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;

/**
 * 页面解析层测试夹具：程序化生成测试 PDF 与构建离线组件（不启动 Spring 容器）。
 *
 * @author Tang_tzb
 */
final class PdfPageTestSupport {

    static final float PAGE_W = 595f;
    static final float PAGE_H = 842f;

    private static final String TEXT_LINE = "Hello PDFBox page parser fixture";

    private PdfPageTestSupport() {
    }

    /**
     * 构建注入好默认阈值的文档级分析器。
     */
    static DefaultPdfAnalyzer buildAnalyzer() {
        DefaultPageAnalyzer pageAnalyzer = new DefaultPageAnalyzer();
        ReflectionTestUtils.setField(pageAnalyzer, "fullImageRatio", 0.85);
        ReflectionTestUtils.setField(pageAnalyzer, "largeImageRatio", 0.20);
        ReflectionTestUtils.setField(pageAnalyzer, "textMinCount", 5);
        return new DefaultPdfAnalyzer(pageAnalyzer);
    }

    /**
     * 构建接入结构化文字提取器的纯文字页解析器（阈值使用声明处默认值）。
     */
    static TextPageParser buildTextPageParser() {
        return new TextPageParser(new com.aifp.aiagent.parser.pdf.text.DefaultPdfTextExtractor(
                new com.aifp.aiagent.parser.pdf.text.PdfCoordinateConverter()));
    }

    /**
     * 构建整页图片页解析器：离线路由测试用，OCR 使用恒返回 EMPTY 的桩
     * （路由验收只验证解析器名与类型，不依赖真实引擎）。
     */
    static ImagePageParser buildImagePageParser() {
        return new ImagePageParser(
                new com.aifp.aiagent.parser.pdf.PageImageRenderer(),
                emptyOcrParser(),
                new com.aifp.aiagent.parser.pdf.text.SimpleCoordinateTransformer());
    }

    /**
     * 恒返回 EMPTY 的 OCR 桩（路由冒烟测试用，不依赖真实引擎）。
     */
    static com.aifp.aiagent.parser.ocr.OcrParser emptyOcrParser() {
        return request -> com.aifp.aiagent.parser.ocr.OcrResult.builder()
                .status(com.aifp.aiagent.parser.ocr.OcrStatus.EMPTY)
                .pages(java.util.List.of())
                .build();
    }

    /**
     * 构建接入真实区域融合链路的混合页解析器（阶段 6），
     * OCR 使用传入桩以便测试捕获请求/构造词级结果。
     */
    static MixedPageParser buildMixedPageParser(com.aifp.aiagent.parser.ocr.OcrParser ocrParser) {
        com.aifp.aiagent.parser.pdf.text.SimpleCoordinateTransformer transformer =
                new com.aifp.aiagent.parser.pdf.text.SimpleCoordinateTransformer();
        com.aifp.aiagent.parser.pdf.region.CoordinateMatcher matcher =
                new com.aifp.aiagent.parser.pdf.region.CoordinateMatcher(transformer);
        return new MixedPageParser(
                new com.aifp.aiagent.parser.pdf.text.DefaultPdfTextExtractor(
                        new com.aifp.aiagent.parser.pdf.text.PdfCoordinateConverter()),
                new com.aifp.aiagent.parser.pdf.PageImageRenderer(),
                ocrParser,
                new com.aifp.aiagent.parser.pdf.region.DefaultRegionAnalyzer(matcher),
                new com.aifp.aiagent.parser.pdf.region.OcrEligibilityEvaluator(),
                matcher);
    }

    /**
     * 构建注册全部内置解析器的路由器。
     */
    static PageParserRouter buildRouter() {
        TextPageParser textPageParser = buildTextPageParser();
        return new PageParserRouter(java.util.List.of(
                textPageParser,
                buildImagePageParser(),
                buildMixedPageParser(emptyOcrParser()),
                new EmptyPageParser()));
    }

    /**
     * 生成 1 页纯文字 PDF。
     */
    static void buildTextPagePdf(File pdf) throws IOException {
        try (PDDocument doc = new PDDocument()) {
            appendTextPage(doc);
            doc.save(pdf);
        }
    }

    /**
     * 生成 1 页整页图片 PDF（IMAGE_ONLY 模拟扫描件，供 OCR 流程测试）。
     */
    static void buildImagePagePdf(File pdf) throws IOException {
        try (PDDocument doc = new PDDocument()) {
            appendImagePage(doc);
            doc.save(pdf);
        }
    }

    /**
     * 生成 1 页"整页图片 + 文字"PDF（MIXED）。
     */
    static void buildMixedPagePdf(File pdf) throws IOException {
        try (PDDocument doc = new PDDocument()) {
            appendMixedPage(doc);
            doc.save(pdf);
        }
    }

    /**
     * 生成 4 页 PDF：文字 / 整页图片 / 图+文 / 空白（路由验收输入）。
     */
    static void buildFourPagePdf(File pdf) throws IOException {
        try (PDDocument doc = new PDDocument()) {
            appendTextPage(doc);
            appendImagePage(doc);
            appendMixedPage(doc);
            doc.addPage(new PDPage());
            doc.save(pdf);
        }
    }

    /**
     * 追加一页纯文字（非空白字符数远超 text-min-count）。
     */
    private static void appendTextPage(PDDocument doc) throws IOException {
        PDPage page = new PDPage();
        doc.addPage(page);
        PDType1Font font = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
        try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
            cs.beginText();
            cs.setFont(font, 12);
            cs.newLineAtOffset(60, PAGE_H - 80);
            cs.showText(TEXT_LINE);
            cs.endText();
        }
    }

    /**
     * 追加一页整页图片（IMAGE_ONLY 模拟扫描件）。
     */
    private static void appendImagePage(PDDocument doc) throws IOException {
        PDPage page = new PDPage();
        doc.addPage(page);
        try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
            cs.drawImage(LosslessFactory.createFromImage(doc, buildImage()),
                    0, 0, PAGE_W, PAGE_H);
        }
    }

    /**
     * 追加一页整页图片 + 叠加文字（MIXED 模拟图片表格 + 文字数据）。
     */
    private static void appendMixedPage(PDDocument doc) throws IOException {
        PDPage page = new PDPage();
        doc.addPage(page);
        PDType1Font font = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
        try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
            cs.drawImage(LosslessFactory.createFromImage(doc, buildImage()),
                    0, 0, PAGE_W, PAGE_H);
            cs.beginText();
            cs.setFont(font, 12);
            cs.newLineAtOffset(60, PAGE_H - 80);
            cs.showText(TEXT_LINE);
            cs.endText();
        }
    }

    /**
     * 构造非纯色测试图片（白底 + 黑块），模拟扫描内容。
     */
    private static BufferedImage buildImage() {
        BufferedImage img = new BufferedImage(200, 200, BufferedImage.TYPE_INT_RGB);
        java.awt.Graphics2D g = img.createGraphics();
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, 200, 200);
        g.setColor(Color.BLACK);
        g.fillRect(50, 50, 100, 100);
        g.dispose();
        return img;
    }
}
