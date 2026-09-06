package com.aifp.aiagent.parser.pdf.text;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * 阶段 5 验收：空间一致性测试。
 * <p>
 * 证明"图片区域与 PDF TextPosition 在允许 DPI 浮点误差、渲染器取整误差和
 * 浮点误差范围内保持空间一致"（pixel error ≤ 2px、IoU > 0.99）：
 * 程序化生成含黑色实心矩形 + 文字的 PDF → TextBlock（TextPosition 链路，
 * PDF 用户空间）经 {@code pdfToImage} 得像素框 → 与渲染图逐像素比对。
 * <p>
 * 页面几何基准：MediaBox + 原点(0,0) + rotation=0；
 * rotation≠0 为已知限制，由 {@link #rotatedPage_knownLimitation} 固化。
 *
 * @author Tang_tzb
 */
class SpatialConsistencyTest {

    private static final float DPI = 300f;
    private static final float K_300 = DPI / 72f;

    /**
     * A4 黑矩形（用户空间）：200×120pt，接近方形以降低 IoU 对边缘 1px 误差的敏感度
     */
    private static final BoundingBox RECT_A4 = BoundingBox.builder()
            .x(100).y(700).width(200).height(120).build();
    /**
     * LETTER 黑矩形（用户空间）
     */
    private static final BoundingBox RECT_LETTER = BoundingBox.builder()
            .x(100).y(650).width(200).height(120).build();

    private static final String MARK_TEXT = "SPATIAL";
    private static final int DARK_THRESHOLD = 100;

    private final SimpleCoordinateTransformer transformer = new SimpleCoordinateTransformer();
    private final DefaultPdfTextExtractor textExtractor =
            new DefaultPdfTextExtractor(new PdfCoordinateConverter());

    // ---------- 验收主用例：A4 595×842 × DPI 矩阵 ----------

    /**
     * 生成含黑色实心矩形 + 黑色文字（黑底黑字保持区域纯黑，TextBlock 照常提取）的单页 PDF。
     */
    private static File buildMarkedPdf(File out, PDRectangle size, int rotation,
                                       BoundingBox rect) throws IOException {
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage();
            page.setMediaBox(size);
            page.setRotation(rotation);
            doc.addPage(page);
            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                cs.setNonStrokingColor(Color.BLACK);
                cs.addRect(rect.getX(), rect.getY(), rect.getWidth(), rect.getHeight());
                cs.fill();
                cs.beginText();
                cs.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 10);
                cs.newLineAtOffset(rect.getX() + 10, rect.getY() + 20);
                cs.showText(MARK_TEXT);
                cs.endText();
            }
            doc.save(out);
        }
        return out;
    }

    // ---------- 非 A4 页面：LETTER @300dpi ----------

    private static boolean isDark(int rgb) {
        int r = (rgb >> 16) & 0xFF;
        int g = (rgb >> 8) & 0xFF;
        int b = rgb & 0xFF;
        return r < DARK_THRESHOLD && g < DARK_THRESHOLD && b < DARK_THRESHOLD;
    }

    // ---------- rotation≠0 已知限制（固化测试） ----------

    /**
     * 断言像素框内缩 inset 后全部为深色（正向空间一致）。
     */
    private static void assertRegionDark(BufferedImage image, BoundingBox pixelBox, int inset) {
        int x0 = (int) Math.ceil(pixelBox.getX()) + inset;
        int y0 = (int) Math.ceil(pixelBox.getY()) + inset;
        int x1 = (int) Math.floor(pixelBox.right()) - inset;
        int y1 = (int) Math.floor(pixelBox.top()) - inset;
        assertThat(x1).as("像素框过小，无法内缩采样").isGreaterThan(x0);
        assertThat(y1).as("像素框过小，无法内缩采样").isGreaterThan(y0);
        long lightCount = 0;
        for (int y = y0; y <= y1; y++) {
            for (int x = x0; x <= x1; x++) {
                if (!isDark(image.getRGB(x, y))) {
                    lightCount++;
                }
            }
        }
        assertThat(lightCount).as("矩形区域应全为深色").isZero();
    }

    // ---------- 构件与断言工具 ----------

    /**
     * 在像素框外扩 3px 的扫描窗内寻找实际黑色像素外接框（逆向空间一致）。
     */
    private static BoundingBox scanDarkBbox(BufferedImage image, BoundingBox pixelBox) {
        int grow = 3;
        int x0 = Math.max(0, (int) Math.floor(pixelBox.getX()) - grow);
        int y0 = Math.max(0, (int) Math.floor(pixelBox.getY()) - grow);
        int x1 = Math.min(image.getWidth() - 1, (int) Math.ceil(pixelBox.right()) + grow);
        int y1 = Math.min(image.getHeight() - 1, (int) Math.ceil(pixelBox.top()) + grow);
        int minX = Integer.MAX_VALUE;
        int minY = Integer.MAX_VALUE;
        int maxX = -1;
        int maxY = -1;
        for (int y = y0; y <= y1; y++) {
            for (int x = x0; x <= x1; x++) {
                if (isDark(image.getRGB(x, y))) {
                    minX = Math.min(minX, x);
                    minY = Math.min(minY, y);
                    maxX = Math.max(maxX, x);
                    maxY = Math.max(maxY, y);
                }
            }
        }
        assertThat(maxX).as("扫描窗内应存在黑色矩形像素").isGreaterThanOrEqualTo(0);
        return BoundingBox.builder()
                .x(minX).y(minY)
                .width(maxX - minX + 1f)
                .height(maxY - minY + 1f)
                .build();
    }

    /**
     * 四边最大偏差（pixel error）。
     */
    private static double maxEdgeDeviation(BoundingBox detected, BoundingBox expected) {
        return Math.max(
                Math.max(Math.abs(detected.getX() - expected.getX()),
                        Math.abs(detected.getY() - expected.getY())),
                Math.max(Math.abs(detected.right() - expected.right()),
                        Math.abs(detected.top() - expected.top())));
    }

    /**
     * 由像素误差预算（每边 ≤2px）推得的 IoU 下界。
     */
    private static double iouErrorBudgetBound(double width, double height) {
        return ((width - 4) * (height - 4)) / (width * height);
    }

    /**
     * 区域内暗像素占比（0~1）。
     */
    private static double darkRatioInRegion(BufferedImage image, BoundingBox pixelBox, int inset) {
        int x0 = (int) Math.ceil(pixelBox.getX()) + inset;
        int y0 = (int) Math.ceil(pixelBox.getY()) + inset;
        int x1 = (int) Math.floor(pixelBox.right()) - inset;
        int y1 = (int) Math.floor(pixelBox.top()) - inset;
        long total = 0;
        long dark = 0;
        for (int y = Math.max(0, y0); y <= Math.min(image.getHeight() - 1, y1); y++) {
            for (int x = Math.max(0, x0); x <= Math.min(image.getWidth() - 1, x1); x++) {
                total++;
                if (isDark(image.getRGB(x, y))) {
                    dark++;
                }
            }
        }
        return total == 0 ? 0d : (double) dark / total;
    }

    /**
     * A4（595×842）× DPI 矩阵（72/150/200/300）：
     * 渲染尺寸正确、计算像素框与实际黑色像素框 pixel error ≤ 2px、
     * IoU 高于该 DPI 的误差预算下界（300dpi 时显式 > 0.99）、
     * TextBlock 像素框与矩形像素框 overlap。
     */
    @ParameterizedTest
    @ValueSource(ints = {72, 150, 200, 300})
    void spatialConsistency_a4_dpiMatrix(int dpi, @TempDir Path tempDir) throws Exception {
        File pdf = buildMarkedPdf(tempDir.resolve("a4.pdf").toFile(),
                PDRectangle.A4, 0, RECT_A4);
        try (PDDocument doc = Loader.loadPDF(pdf)) {
            PDRectangle mediaBox = doc.getPage(0).getMediaBox();
            BufferedImage image = new PDFRenderer(doc).renderImageWithDPI(0, dpi);

            // 渲染尺寸（取整误差 ≤1px，断言容差 2px）
            assertThat((double) image.getWidth())
                    .isCloseTo(mediaBox.getWidth() * dpi / 72d, within(2.0));
            assertThat((double) image.getHeight())
                    .isCloseTo(mediaBox.getHeight() * dpi / 72d, within(2.0));

            // PDF 框 → 像素框（换算基准与渲染一致：MediaBox 页高）
            BoundingBox pixelRect = transformer.pdfToImage(RECT_A4, dpi, mediaBox.getHeight());

            // 正向：计算像素框内缩 2px 全为深色（AA 边缘外）
            assertRegionDark(image, pixelRect, 2);

            // 逆向：实际黑色像素 bbox vs 计算框 → pixel error ≤ 2px + IoU
            BoundingBox blackBbox = scanDarkBbox(image, pixelRect);
            assertThat(maxEdgeDeviation(blackBbox, pixelRect))
                    .as("pixel error 应 ≤ 2px (dpi=%d)", dpi)
                    .isLessThanOrEqualTo(2.0);
            double budgetBound = iouErrorBudgetBound(pixelRect.getWidth(), pixelRect.getHeight());
            if (dpi >= 200) {
                // 验收量化阈值：IoU > 0.99（300dpi 验收用例所在档）
                assertThat(blackBbox.iou(pixelRect))
                        .as("IoU 应 > 0.99 (dpi=%d)", dpi)
                        .isGreaterThan(0.99);
            } else {
                // 低 DPI 下 1px AA 绝对误差占比更大，按像素误差预算推 IoU 下界
                assertThat(blackBbox.iou(pixelRect))
                        .as("IoU 应高于误差预算下界 (dpi=%d)", dpi)
                        .isGreaterThan(budgetBound);
            }

            // TextBlock（TextPosition 链路）像素框与矩形像素框 overlap
            TextBlock textBlock = findBlockContaining(doc, MARK_TEXT);
            BoundingBox textPixel = transformer.pdfToImage(
                    textBlock.getBbox(), dpi, mediaBox.getHeight());
            assertThat(textPixel.overlap(pixelRect))
                    .as("文字 TextBlock 像素框应与矩形像素框 overlap (dpi=%d)", dpi)
                    .isTrue();
        }
    }

    /**
     * 非 A4（LETTER 612×792）@300dpi：同套空间一致性断言。
     */
    @Test
    void spatialConsistency_letter_at300dpi(@TempDir Path tempDir) throws Exception {
        File pdf = buildMarkedPdf(tempDir.resolve("letter.pdf").toFile(),
                PDRectangle.LETTER, 0, RECT_LETTER);
        try (PDDocument doc = Loader.loadPDF(pdf)) {
            PDRectangle mediaBox = doc.getPage(0).getMediaBox();
            BufferedImage image = new PDFRenderer(doc).renderImageWithDPI(0, (int) DPI);

            assertThat((double) image.getWidth())
                    .isCloseTo(mediaBox.getWidth() * K_300, within(2.0));
            assertThat((double) image.getHeight())
                    .isCloseTo(mediaBox.getHeight() * K_300, within(2.0));

            BoundingBox pixelRect = transformer.pdfToImage(RECT_LETTER, DPI, mediaBox.getHeight());
            assertRegionDark(image, pixelRect, 2);

            BoundingBox blackBbox = scanDarkBbox(image, pixelRect);
            assertThat(maxEdgeDeviation(blackBbox, pixelRect)).isLessThanOrEqualTo(2.0);
            assertThat(blackBbox.iou(pixelRect)).isGreaterThan(0.99);

            TextBlock textBlock = findBlockContaining(doc, MARK_TEXT);
            BoundingBox textPixel = transformer.pdfToImage(
                    textBlock.getBbox(), DPI, mediaBox.getHeight());
            assertThat(textPixel.overlap(pixelRect)).isTrue();
        }
    }

    /**
     * rotation≠0 已知限制测试：/Rotate 90 页面渲染时宽高换边，实际内容按
     * 旋转后映射呈现；当前实现按 rotation=0 基准换算 → 计算区域与渲染内容
     * 不一致（暗像素占比远低于半），同时可按旋转映射关系找到实际矩形——
     * 将限制固化为可执行文档。实现旋转支持后，本测试应改为正向断言。
     */
    @Test
    void rotatedPage_knownLimitation(@TempDir Path tempDir) throws Exception {
        File pdf = buildMarkedPdf(tempDir.resolve("rot90.pdf").toFile(),
                PDRectangle.A4, 90, RECT_A4);
        try (PDDocument doc = Loader.loadPDF(pdf)) {
            BufferedImage image = new PDFRenderer(doc).renderImageWithDPI(0, (int) DPI);

            // 渲染器已应用旋转：显示宽高换边（842×595pt → 3508×2480px）
            assertThat((double) image.getWidth())
                    .isCloseTo(842d * K_300, within(2.0));
            assertThat((double) image.getHeight())
                    .isCloseTo(595d * K_300, within(2.0));

            // 当前实现按 rotation=0 基准（MediaBox + 原点(0,0)）换算 → 区域基本为白
            BoundingBox assumed = transformer.pdfToImage(RECT_A4, DPI, 842f);
            assertThat(darkRatioInRegion(image, assumed, 0))
                    .as("rotation=0 基准换算区域不应命中旋转后的矩形（限制固化）")
                    .isLessThan(0.5);

            // 旋转映射（/Rotate 90 顺时针：display_x = y_user·k，display_y = x_user·k）
            // 可定位到实际矩形，证明内容存在且换算基准与渲染不一致
            BoundingBox actualRotated = BoundingBox.builder()
                    .x(RECT_A4.getY() * K_300)
                    .y(RECT_A4.getX() * K_300)
                    .width(RECT_A4.getHeight() * K_300)
                    .height(RECT_A4.getWidth() * K_300)
                    .build();
            assertRegionDark(image, actualRotated, 2);
        }
    }

    private TextBlock findBlockContaining(PDDocument doc, String keyword) {
        return textExtractor.extract(doc, 0).getBlocks().stream()
                .filter(b -> b.getText().contains(keyword))
                .findFirst()
                .orElseThrow(() -> new AssertionError("未提取到含 " + keyword + " 的 TextBlock"));
    }
}
