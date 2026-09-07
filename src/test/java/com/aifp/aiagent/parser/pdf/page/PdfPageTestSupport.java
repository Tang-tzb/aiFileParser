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
     * 构建接入真实区域融合链路的混合页解析器（阶段 6/7），
     * OCR 使用传入桩以便测试捕获请求/构造词级结果；
     * 表格识别使用真实 Hybrid 链路（图像线检测 + 坐标聚类 + 表头候选）。
     */
    static MixedPageParser buildMixedPageParser(com.aifp.aiagent.parser.ocr.OcrParser ocrParser) {
        return buildMixedPageParser(ocrParser, buildHybridTableRecognizer(ocrParser));
    }

    /**
     * 构建真实混合表格识别器（阶段 7，离线默认阈值，OCR 使用传入桩）。
     */
    static com.aifp.aiagent.parser.pdf.layout.TableStructureRecognizer buildHybridTableRecognizer(
            com.aifp.aiagent.parser.ocr.OcrParser ocrParser) {
        com.aifp.aiagent.parser.pdf.text.SimpleCoordinateTransformer transformer =
                new com.aifp.aiagent.parser.pdf.text.SimpleCoordinateTransformer();
        com.aifp.aiagent.parser.pdf.region.CoordinateMatcher matcher =
                new com.aifp.aiagent.parser.pdf.region.CoordinateMatcher(transformer);
        return new com.aifp.aiagent.parser.pdf.layout.HybridTableRecognizer(
                new com.aifp.aiagent.parser.pdf.layout.ImageTableRecognizer(matcher),
                new com.aifp.aiagent.parser.pdf.layout.CoordinateTableRecognizer(),
                new com.aifp.aiagent.parser.pdf.layout.HeaderCandidateDetector(matcher),
                matcher,
                ocrParser);
    }

    /**
     * 构建混合页解析器（自定义表格识别器，供表格触发/降级测试注入桩）。
     */
    static MixedPageParser buildMixedPageParser(com.aifp.aiagent.parser.ocr.OcrParser ocrParser,
                                                com.aifp.aiagent.parser.pdf.layout.TableStructureRecognizer tableRecognizer) {
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
                matcher,
                tableRecognizer);
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
     * 生成 1 页矢量表格线 + 原生文字 PDF（阶段 7 端到端夹具）。
     * <p>
     * 表格：外框 (100,300)-(500,600)，竖线 x=300，横线 y=400/500（2 列 × 3 行）。
     * 字形 bbox 语义（12pt）：y ∈ [基线−12, 基线]；标签/值基线错开（行聚类分离）
     * 且块间距 &gt; 14.4pt（避免跨列同行聚合），第三行值为两行文字（多行一格场景）。
     */
    static void buildTablePagePdf(File pdf) throws IOException {
        // MediaBox 必须显式对齐 PAGE_W×PAGE_H（默认为 LETTER 612×792，
        // 会导致 profile.pageHeight 与真实页高不一致，像素↔PDF 反算 y 整体偏移）
        PDPage page = new PDPage(new org.apache.pdfbox.pdmodel.common.PDRectangle(PAGE_W, PAGE_H));
        try (PDDocument doc = new PDDocument()) {
            doc.addPage(page);
            PDType1Font font = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                cs.setLineWidth(1f);
                float[][] lines = {
                        {100, 300, 500, 300}, {100, 400, 500, 400},
                        {100, 500, 500, 500}, {100, 600, 500, 600},
                        {100, 300, 100, 600}, {300, 300, 300, 600}, {500, 300, 500, 600}};
                for (float[] l : lines) {
                    cs.moveTo(l[0], l[1]);
                    cs.lineTo(l[2], l[3]);
                    cs.stroke();
                }
                // 行 1（y 500..600）：标签 580 / 值 550
                tableText(cs, font, "DanWei", 120, 580);
                tableText(cs, font, "XXGS", 320, 550);
                // 行 2（y 400..500）：标签 480 / 值 450
                tableText(cs, font, "MianJi", 120, 480);
                tableText(cs, font, "1000pm", 320, 450);
                // 行 3（y 300..400）：标签 390 / 两行值 360、342（同块多行）
                tableText(cs, font, "DiDian", 120, 390);
                tableText(cs, font, "CityA", 320, 360);
                tableText(cs, font, "Road88", 320, 342);
            }
            doc.save(pdf);
        }
    }

    /**
     * 表格页单行文字（Helvetica 12pt）。
     */
    private static void tableText(PDPageContentStream cs, PDType1Font font,
                                  String text, float x, float baseline) throws IOException {
        cs.beginText();
        cs.setFont(font, 12);
        cs.newLineAtOffset(x, baseline);
        cs.showText(text);
        cs.endText();
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
