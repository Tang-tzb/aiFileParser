package com.aifp.aiagent.parser.pdf.text;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.File;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * {@link DefaultPdfTextExtractor} 验收测试：
 * PDF → TextPosition → TextBlock，含 Y 聚行、行内 X 排序、多行分块与字体信息。
 *
 * @author Tang_tzb
 */
class DefaultPdfTextExtractorTest {

    private static final float PAGE_H = 842f;

    private static DefaultPdfTextExtractor extractor;
    @TempDir
    Path tempDir;

    @BeforeAll
    static void setUp() {
        extractor = new DefaultPdfTextExtractor(new PdfCoordinateConverter());
        ReflectionTestUtils.setField(extractor, "lineToleranceRatio", 0.50);
        ReflectionTestUtils.setField(extractor, "spaceGapRatio", 0.25);
        ReflectionTestUtils.setField(extractor, "blockGapRatio", 1.20);
    }

    @Test
    void extract_groupsLinesByY_andBlocksByGap() throws Exception {
        File pdf = tempDir.resolve("structured.pdf").toFile();
        buildStructuredPdf(pdf);

        try (PDDocument doc = Loader.loadPDF(pdf)) {
            PdfText text = extractor.extract(doc, 0);

            assertThat(text.getPageNumber()).isEqualTo(1);
            // 段落 1（两行 12pt）+ 段落 2（100pt 间隔 16pt 粗体）→ 2 个块
            assertThat(text.getBlocks()).hasSize(2);

            TextBlock block1 = text.getBlocks().get(0);
            assertThat(block1.getLines()).hasSize(2);
            // 行内 X 排序 + 几何间隙插空格
            assertThat(block1.getLines().get(0).getText()).isEqualTo("AAAA BBBB");
            assertThat(block1.getLines().get(1).getText()).isEqualTo("CCCC");
            // 块文字按阅读顺序（自上而下）
            assertThat(block1.getText()).isEqualTo("AAAA BBBB\nCCCC");
            // 主字体：块 1 按字符数加权取 12pt Helvetica
            assertThat(block1.getFontSize()).isCloseTo(12f, within(0.5f));
            assertThat(block1.getFontName()).contains("Helvetica");
            // 验收字段链：Page → TextBlock → BoundingBox
            assertThat(block1.getPage()).isEqualTo(1);
            assertThat(block1.getBbox().getX()).isBetween(55f, 65f);
            assertThat(block1.getBbox().getWidth()).isGreaterThan(0);
            assertThat(block1.getBbox().getY()).isGreaterThan(0);
            assertThat(block1.getBbox().top()).isLessThanOrEqualTo(PAGE_H);

            TextBlock block2 = text.getBlocks().get(1);
            assertThat(block2.getLines()).hasSize(1);
            // 空格字符步进 0.278em > space-gap-ratio 0.25 → 几何空格还原
            assertThat(block2.getText()).isEqualTo("EEEE FFFF");
            assertThat(block2.getFontSize()).isCloseTo(16f, within(0.5f));
            assertThat(block2.getFontName()).contains("Bold");
            // 用户空间 y 向上：块 2 在页面上更靠下
            assertThat(block2.getBbox().getY()).isLessThan(block1.getBbox().getY());

            // 旧全文能力保留
            assertThat(text.toPlainText()).contains("AAAA BBBB", "CCCC", "EEEE FFFF");
        }
    }

    @Test
    void extract_blankPage_returnsEmptyBlocksAndPlainEmptyText(@TempDir Path tempDir) throws Exception {
        File pdf = tempDir.resolve("blank.pdf").toFile();
        try (PDDocument doc = new PDDocument()) {
            doc.addPage(new PDPage());
            doc.save(pdf);
        }

        try (PDDocument doc = Loader.loadPDF(pdf)) {
            PdfText text = extractor.extract(doc, 0);

            assertThat(text.getBlocks()).isEmpty();
            assertThat(text.toPlainText()).isEmpty();
        }
    }

    /**
     * 生成结构化测试页：
     * 段落 1 = AAAA(60,780) BBBB(200,780) 同基线两段 + CCCC(60,762) 下一行；
     * 段落 2 = EEEE FFFF(60,662) 16pt 粗体，与段落 1 垂直间隔约 100pt。
     */
    private void buildStructuredPdf(File pdf) throws Exception {
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage();
            doc.addPage(page);
            PDType1Font regular = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
            PDType1Font bold = new PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD);
            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                drawText(cs, regular, 12, 60, 780, "AAAA");
                drawText(cs, regular, 12, 200, 780, "BBBB");
                drawText(cs, regular, 12, 60, 762, "CCCC");
                drawText(cs, bold, 16, 60, 662, "EEEE FFFF");
            }
            doc.save(pdf);
        }
    }

    private void drawText(PDPageContentStream cs, PDType1Font font,
                          float size, float x, float y, String text) throws Exception {
        cs.beginText();
        cs.setFont(font, size);
        cs.newLineAtOffset(x, y);
        cs.showText(text);
        cs.endText();
    }
}
