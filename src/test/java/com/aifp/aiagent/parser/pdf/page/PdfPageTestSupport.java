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
     * 构建注册全部内置解析器的路由器。
     */
    static PageParserRouter buildRouter() {
        TextPageParser textPageParser = new TextPageParser();
        return new PageParserRouter(java.util.List.of(
                textPageParser,
                new ImagePageParser(),
                new MixedPageParser(textPageParser),
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
