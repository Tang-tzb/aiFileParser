package com.aifp.aiagent.parser.pdf.region;

import com.aifp.aiagent.parser.pdf.text.BoundingBox;
import com.aifp.aiagent.parser.pdf.text.SimpleCoordinateTransformer;
import com.aifp.aiagent.parser.pdf.text.TextBlock;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory;
import org.junit.jupiter.api.Test;

import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * {@link DefaultRegionAnalyzer} 测试：合成 PDF + 当页渲染图注入（72 DPI，k=1，
 * 像素坐标 = PDF 坐标 Y 轴翻转）。覆盖占位提取、分类启发式、表格候选标记
 * （仅候选，非表格识别）与 coverage/textCandidate 基础数据。
 *
 * @author Tang_tzb
 */
class DefaultRegionAnalyzerTest {

    private static final float PAGE_W = 595f;
    private static final float PAGE_H = 842f;

    private final DefaultRegionAnalyzer analyzer =
            new DefaultRegionAnalyzer(new CoordinateMatcher(new SimpleCoordinateTransformer()));

    // ---------- 夹具 ----------

    /**
     * PDF 坐标矩形 → 渲染图像素矩形（72 DPI，y 翻转）。
     */
    private static int pixelY(float pdfY, float h) {
        return Math.round(PAGE_H - h - pdfY);
    }

    /**
     * 生成单页 PDF，并在 (x, y, w×h) 处绘制同一图片 repeat 次（占位提取输入）。
     */
    private PDDocument pageWithImage(BoundingBox rect, int repeat) throws IOException {
        PDDocument doc = new PDDocument();
        PDPage page = new PDPage(new PDRectangle(PAGE_W, PAGE_H));
        doc.addPage(page);
        BufferedImage content = new BufferedImage(50, 50, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = content.createGraphics();
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, 50, 50);
        g.dispose();
        try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
            for (int i = 0; i < repeat; i++) {
                cs.drawImage(LosslessFactory.createFromImage(doc, content),
                        rect.getX(), rect.getY(), rect.getWidth(), rect.getHeight());
            }
        }
        return doc;
    }

    /**
     * 构建当页"渲染图"（72 DPI：像素 = pt，y 翻转），底色白 + 任意绘制回调。
     */
    private BufferedImage renderedPage(java.util.function.Consumer<Graphics2D> painter) {
        BufferedImage image = new BufferedImage((int) PAGE_W, (int) PAGE_H, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, image.getWidth(), image.getHeight());
        if (painter != null) {
            painter.accept(g);
        }
        g.dispose();
        return image;
    }

    private TextBlock block(float x, float y, float w, float h) {
        return TextBlock.builder()
                .text("t")
                .bbox(BoundingBox.builder().x(x).y(y).width(w).height(h).build())
                .fontSize(12f).fontName("Helvetica").page(1)
                .build();
    }

    // ---------- 图片占位提取与分类 ----------

    @Test
    void imageRegion_bboxMatchesPlacement() throws IOException {
        BoundingBox rect = BoundingBox.builder().x(100).y(400).width(200).height(150).build();
        try (PDDocument doc = pageWithImage(rect, 1)) {
            List<VisualRegion> regions = analyzer.analyze(doc, 0, List.of(), renderedPage(null), 72f);

            assertThat(regions).hasSize(1);
            VisualRegion region = regions.get(0);
            assertThat(region.getRegionType()).isEqualTo(RegionType.IMAGE);
            assertThat(region.getBbox().getX()).isCloseTo(100f, within(0.5f));
            assertThat(region.getBbox().getY()).isCloseTo(400f, within(0.5f));
            assertThat(region.getBbox().getWidth()).isCloseTo(200f, within(0.5f));
            assertThat(region.getBbox().getHeight()).isCloseTo(150f, within(0.5f));
            // 白色区域：无文字带 → 视觉文本候选分为 0
            assertThat(region.getTextCandidateScore()).isCloseTo(0.0, within(1e-6));
        }
    }

    @Test
    void redImage_classifiedAsStamp() throws IOException {
        BoundingBox rect = BoundingBox.builder().x(100).y(400).width(200).height(150).build();
        BufferedImage rendered = renderedPage(g -> {
            g.setColor(Color.RED);
            g.fillRect(100, pixelY(400f, 150f), 200, 150);
        });
        try (PDDocument doc = pageWithImage(rect, 1)) {
            List<VisualRegion> regions = analyzer.analyze(doc, 0, List.of(), rendered, 72f);
            assertThat(regions).hasSize(1);
            assertThat(regions.get(0).getRegionType()).isEqualTo(RegionType.STAMP);
        }
    }

    @Test
    void sparseInkInLowerHalf_classifiedAsSignature() throws IOException {
        // 下半部（中心 y=175 < 421）+ 细笔画低墨迹（≈4%）→ 签名启发式
        BoundingBox rect = BoundingBox.builder().x(100).y(100).width(200).height(150).build();
        BufferedImage rendered = renderedPage(g -> {
            g.setColor(Color.BLACK);
            g.fillRect(120, pixelY(220f, 2), 160, 2);
            g.fillRect(120, pixelY(180f, 2), 160, 2);
            g.fillRect(120, pixelY(140f, 2), 160, 2);
        });
        try (PDDocument doc = pageWithImage(rect, 1)) {
            List<VisualRegion> regions = analyzer.analyze(doc, 0, List.of(), rendered, 72f);
            assertThat(regions).hasSize(1);
            assertThat(regions.get(0).getRegionType()).isEqualTo(RegionType.SIGNATURE);
        }
    }

    @Test
    void textBandInRegion_producesTextCandidateScore() throws IOException {
        // 单条 20pt 高的满宽暗带 → 1 个文本带 → score = min(1, 1/3)
        BoundingBox rect = BoundingBox.builder().x(100).y(400).width(200).height(150).build();
        BufferedImage rendered = renderedPage(g -> {
            g.setColor(Color.BLACK);
            g.fillRect(100, pixelY(500f, 20), 200, 20);
        });
        try (PDDocument doc = pageWithImage(rect, 1)) {
            List<VisualRegion> regions = analyzer.analyze(doc, 0, List.of(), rendered, 72f);
            assertThat(regions).hasSize(1);
            assertThat(regions.get(0).getTextCandidateScore()).isCloseTo(1 / 3d, within(0.05));
        }
    }

    @Test
    void duplicatedPlacements_mergedIntoOneRegion() throws IOException {
        BoundingBox rect = BoundingBox.builder().x(100).y(400).width(200).height(150).build();
        try (PDDocument doc = pageWithImage(rect, 3)) {
            List<VisualRegion> regions = analyzer.analyze(doc, 0, List.of(), renderedPage(null), 72f);
            assertThat(regions).hasSize(1);
        }
    }

    @Test
    void coverageRatio_computedFromTextBlocks() throws IOException {
        BoundingBox rect = BoundingBox.builder().x(100).y(400).width(200).height(150).build();
        // 文字块覆盖区域左半 → coverage = 100x150 / 200x150 = 0.5
        List<TextBlock> textBlocks = List.of(block(100, 400, 100, 150));
        try (PDDocument doc = pageWithImage(rect, 1)) {
            List<VisualRegion> regions = analyzer.analyze(doc, 0, textBlocks, renderedPage(null), 72f);
            assertThat(regions).hasSize(1);
            assertThat(regions.get(0).getCoverageRatio()).isCloseTo(0.5, within(1e-6));
        }
    }

    // ---------- 表格候选（原生文字网格，仅标记） ----------

    @Test
    void alignedTextGrid_markedAsTableCandidate() throws IOException {
        // 两列对齐（x=100 / x=300），每列 3 块 → TABLE 候选；tableScore = min(1, 2/4)
        List<TextBlock> textBlocks = List.of(
                block(100, 600, 120, 14), block(100, 570, 120, 14), block(100, 540, 120, 14),
                block(300, 600, 120, 14), block(300, 570, 120, 14), block(300, 540, 120, 14));
        try (PDDocument doc = new PDDocument()) {
            doc.addPage(new PDPage(new PDRectangle(PAGE_W, PAGE_H)));
            List<VisualRegion> regions = analyzer.analyze(doc, 0, textBlocks, renderedPage(null), 72f);

            assertThat(regions).hasSize(1);
            VisualRegion table = regions.get(0);
            assertThat(table.getRegionType()).isEqualTo(RegionType.TABLE);
            assertThat(table.isLikelyTable()).isTrue();
            assertThat(table.getTableScore()).isCloseTo(0.5, within(1e-6));
            // bbox = 参与网格文字块的并集
            assertThat(table.getBbox().getX()).isCloseTo(100f, within(0.5f));
            assertThat(table.getBbox().getY()).isCloseTo(540f, within(0.5f));
            assertThat(table.getBbox().getWidth()).isCloseTo(320f, within(0.5f));
            assertThat(table.getBbox().getHeight()).isCloseTo(74f, within(0.5f));
            assertThat(table.getDescription()).contains("仅候选");
        }
    }

    @Test
    void unalignedText_noTableCandidate() throws IOException {
        // 单列（x 全对齐但仅 1 列）→ 非表格候选
        List<TextBlock> textBlocks = List.of(
                block(100, 600, 120, 14), block(100, 570, 120, 14),
                block(100, 540, 120, 14), block(100, 510, 120, 14));
        try (PDDocument doc = new PDDocument()) {
            doc.addPage(new PDPage(new PDRectangle(PAGE_W, PAGE_H)));
            List<VisualRegion> regions = analyzer.analyze(doc, 0, textBlocks, renderedPage(null), 72f);
            assertThat(regions).isEmpty();
        }
    }
}
