package com.aifp.aiagent.parser.pdf;

import com.aifp.aiagent.exception.BusinessException;
import com.aifp.aiagent.parser.pdf.ast.*;
import com.aifp.aiagent.parser.pdf.page.EmptyPageParser;
import com.aifp.aiagent.parser.pdf.page.PageParserRouter;
import com.aifp.aiagent.parser.pdf.page.TextPageParser;
import com.aifp.aiagent.parser.pdf.structure.KeyValueRecognizer;
import com.aifp.aiagent.parser.pdf.structure.TitleRecognizer;
import com.aifp.aiagent.parser.pdf.text.DefaultPdfTextExtractor;
import com.aifp.aiagent.parser.pdf.text.PdfCoordinateConverter;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link PdfDocumentParser} 端到端单元测试（阶段 9，离线）：
 * PdfAnalyzer（真实检测）→ PageParserRouter（真实注册表）→ PageParser（真实文字提取）
 * → DocumentAstAssembler → DocumentAst。
 * 覆盖：TEXT_ONLY 统一 AST（含标题/阅读序/键值空列表）、多页页序、EMPTY 页路由、
 * 非法文件报错。三类 PDF 统一出口——IMAGE_ONLY/MIXED 真实链路探针见阶段验收（M 探针）。
 *
 * @author Tang_tzb
 */
class PdfDocumentParserTest {

    private static final float PAGE_W = 595f;
    private static final float PAGE_H = 842f;
    @TempDir
    Path tempDir;
    private PdfDocumentParser parser;

    @BeforeEach
    void setUp() {
        // 文档级检测器（阈值与生产默认一致）
        DefaultPageAnalyzer pageAnalyzer = new DefaultPageAnalyzer();
        ReflectionTestUtils.setField(pageAnalyzer, "fullImageRatio", 0.85);
        ReflectionTestUtils.setField(pageAnalyzer, "largeImageRatio", 0.20);
        ReflectionTestUtils.setField(pageAnalyzer, "textMinCount", 5);
        PdfAnalyzer analyzer = new DefaultPdfAnalyzer(pageAnalyzer);

        // 路由表：TEXT_ONLY / EMPTY（离线场景无需 OCR/图片链路）
        TextPageParser textPageParser = new TextPageParser(
                new DefaultPdfTextExtractor(new PdfCoordinateConverter()));
        PageParserRouter router = new PageParserRouter(List.of(textPageParser, new EmptyPageParser()));

        // AST 组装链（真实识别器，阈值离线注入）
        TitleRecognizer titleRecognizer = new TitleRecognizer();
        ReflectionTestUtils.setField(titleRecognizer, "titleFontRatio", 1.2);
        DocumentAstAssembler astAssembler = new DocumentAstAssembler(new PageNodeAssembler(
                new TableNodeAssembler(), new KeyValueRecognizer(), titleRecognizer));

        parser = new PdfDocumentParser(analyzer, router, astAssembler);
    }

    @Test
    void parse_textOnlyPdf_unifiedAstWithReadingOrder() throws Exception {
        // 三类 PDF 统一出口：TEXT_ONLY → DocumentAst（约束 3 的结构载体）
        File pdf = tempDir.resolve("text-only.pdf").toFile();
        buildTextPdf(pdf, "Project Acceptance Report",
                "Body paragraph one for acceptance check",
                "Body paragraph two for parser verification");

        DocumentAst ast = parser.parse(pdf);

        // 文档级元数据
        assertThat(ast.getFileName()).isEqualTo("text-only.pdf");
        assertThat(ast.getMetadata().getTotalPages()).isEqualTo(1);
        assertThat(ast.getMetadata().getDocumentType()).isEqualTo(PdfContentType.TEXT_ONLY);
        assertThat(UUID.fromString(ast.getDocumentId())).isNotNull();

        // 页级：标题识别 + 键值空列表（无表格来源）
        assertThat(ast.getPages()).hasSize(1);
        PageNode page = ast.getPages().get(0);
        assertThat(page.getPageNumber()).isEqualTo(1);
        assertThat(page.getContentType()).isEqualTo(PageContentType.TEXT_ONLY);
        assertThat(page.getKeyValues()).isEmpty();

        // 节点：大字号短文本在前为标题，正文段落随后
        assertThat(page.getNodes().get(0)).isInstanceOf(TitleNode.class);
        assertThat(((TitleNode) page.getNodes().get(0)).getText())
                .isEqualTo("Project Acceptance Report");
        assertThat(page.getNodes())
                .extracting(DocumentNode::getType)
                .contains(DocumentNodeType.TITLE,
                        DocumentNodeType.PARAGRAPH, DocumentNodeType.PARAGRAPH);

        // 约束 1：确定性阅读序——有 bbox 节点自上而下（top 单调不增）
        float prevTop = Float.MAX_VALUE;
        for (DocumentNode node : page.getNodes()) {
            if (node.getBbox() != null) {
                float top = node.getBbox().getY() + node.getBbox().getHeight();
                assertThat(top).as("阅读序自上而下").isLessThanOrEqualTo(prevTop);
                prevTop = top;
            }
        }
    }

    @Test
    void parse_multiPage_pageOrderPreserved() throws Exception {
        // 页序 = PDF 页序，逐页内容不串页
        File pdf = tempDir.resolve("two-pages.pdf").toFile();
        try (PDDocument doc = new PDDocument()) {
            appendTextLine(doc, "Marker Page One", 12);
            appendTextLine(doc, "Marker Page Two", 12);
            doc.save(pdf);
        }

        DocumentAst ast = parser.parse(pdf);

        assertThat(ast.getMetadata().getTotalPages()).isEqualTo(2);
        assertThat(ast.getPages()).extracting(PageNode::getPageNumber).containsExactly(1, 2);
        assertThat(firstText(ast.getPages().get(0))).contains("Marker Page One");
        assertThat(firstText(ast.getPages().get(1))).contains("Marker Page Two");
    }

    @Test
    void parse_blankPage_routedToEmptyParser() throws Exception {
        // 第 2 页空白 → EMPTY 路由，节点为空且无异常
        File pdf = tempDir.resolve("with-blank.pdf").toFile();
        try (PDDocument doc = new PDDocument()) {
            appendTextLine(doc, "Only page one has content here", 12);
            doc.addPage(new PDPage());
            doc.save(pdf);
        }

        DocumentAst ast = parser.parse(pdf);

        assertThat(ast.getPages()).hasSize(2);
        PageNode blank = ast.getPages().get(1);
        assertThat(blank.getContentType()).isEqualTo(PageContentType.EMPTY);
        assertThat(blank.getNodes()).isEmpty();
        assertThat(blank.getKeyValues()).isEmpty();
    }

    @Test
    void parse_notPdfFile_throwsBusinessException() throws Exception {
        // 非法文件 → IOException 转译为业务异常（不泄漏底层堆栈语义）
        File file = tempDir.resolve("broken.pdf").toFile();
        Files.writeString(file.toPath(), "this is not a pdf document");

        assertThatThrownBy(() -> parser.parse(file))
                .isInstanceOf(BusinessException.class);
    }

    // ---------- 夹具 ----------

    /**
     * 生成单页三行文字 PDF：标题 18pt（≥12×1.2）+ 两行正文 12pt，
     * 行距 40pt 大于块合并阈值（1.2×字号），保证 3 个独立文字块。
     */
    private void buildTextPdf(File pdf, String title, String body1, String body2) throws Exception {
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage();
            doc.addPage(page);
            PDType1Font font = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                cs.beginText();
                cs.setFont(font, 18);
                cs.newLineAtOffset(60, PAGE_H - 80);
                cs.showText(title);
                cs.endText();
                cs.beginText();
                cs.setFont(font, 12);
                cs.newLineAtOffset(60, PAGE_H - 120);
                cs.showText(body1);
                cs.endText();
                cs.beginText();
                cs.setFont(font, 12);
                cs.newLineAtOffset(60, PAGE_H - 160);
                cs.showText(body2);
                cs.endText();
            }
            doc.save(pdf);
        }
    }

    /**
     * 追加一页单行文字。
     */
    private void appendTextLine(PDDocument doc, String line, float fontSize) throws Exception {
        PDPage page = new PDPage();
        doc.addPage(page);
        PDType1Font font = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
        try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
            cs.beginText();
            cs.setFont(font, fontSize);
            cs.newLineAtOffset(60, PAGE_H - 80);
            cs.showText(line);
            cs.endText();
        }
    }

    /**
     * 取页内首个段落/标题文本（断言用）。
     */
    private String firstText(PageNode page) {
        DocumentNode node = page.getNodes().get(0);
        if (node instanceof TitleNode title) {
            return title.getText();
        }
        if (node instanceof com.aifp.aiagent.parser.pdf.ast.ParagraphNode paragraph) {
            return paragraph.getText();
        }
        return "";
    }
}
