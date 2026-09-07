package com.aifp.aiagent.parser.pdf.layout;

import com.aifp.aiagent.parser.ocr.*;
import com.aifp.aiagent.parser.pdf.page.ElementSource;
import com.aifp.aiagent.parser.pdf.region.CoordinateMatcher;
import com.aifp.aiagent.parser.pdf.text.BoundingBox;
import com.aifp.aiagent.parser.pdf.text.SimpleCoordinateTransformer;
import com.aifp.aiagent.parser.pdf.text.TextBlock;
import com.aifp.aiagent.parser.pdf.text.TextLine;
import org.junit.jupiter.api.Test;

import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * {@link HybridTableRecognizer} 编排单元测试：
 * 图像格网择优与文字包住率回退、覆盖率主判定 + 中心点辅助的值绑定、
 * 多行块完整绑定（建设地点场景）、表头候选 OCR 与 FUSION 绑定、
 * OCR 失败优雅降级、临时裁剪文件即用即删、异常封闭。
 *
 * @author Tang_tzb
 */
class HybridTableRecognizerTest {

    private static final int IMG_W = 300;
    private static final int IMG_H = 400;
    private static final float DPI = 72f;
    private static final int[] XS = {50, 150, 250};
    private static final int[] YS = {50, 150, 250, 350};

    private final SimpleCoordinateTransformer transformer = new SimpleCoordinateTransformer();
    private final CoordinateMatcher matcher = new CoordinateMatcher(transformer);
    private final StubOcr ocr = new StubOcr();

    // ---------- 格网择优 ----------

    @Test
    void imageGridAdopted_valueBound_noHeaderOcrOnBlankCells() {
        // 文字行中心落在格网内 → 包住率 1.0 → 采用图像格网
        TableGrid grid = singleGrid(input(gridImage(),
                block("单位名称", 80, 290, 40, 12)));

        assertThat(grid.getRowCount()).isEqualTo(3);
        assertThat(grid.getColumnCount()).isEqualTo(2);
        // 空骨架单元格被过滤，仅剩值单元格
        assertThat(grid.getCells()).hasSize(1);
        TableCell cell = grid.getCells().get(0);
        assertThat(cell.getRowIndex()).isZero();
        assertThat(cell.getColumnIndex()).isZero();
        assertThat(cell.getValue()).isEqualTo("单位名称");
        assertThat(cell.getSource()).isEqualTo(ElementSource.PDF_TEXT);
        assertThat(cell.getConfidence()).isCloseTo(1.0f, within(0.01f));
        // 像素格网 → PDF 用户空间（Y 翻转）：cell(0,0) = (50,250,100,100)
        assertThat(cell.getBoundingBox().getX()).isCloseTo(50f, within(0.6f));
        assertThat(cell.getBoundingBox().getY()).isCloseTo(250f, within(0.6f));
        // 空白格不触发表头 OCR
        assertThat(ocr.requests).isEmpty();
    }

    @Test
    void noImageGrid_fallbackToCoordinateClustering() {
        // 白图无线条 → 方式一空 → 方式二聚类 2×2
        TableGrid grid = singleGrid(input(whiteImage(),
                block("单位名称", 40, 330, 80, 12),
                block("XX公司", 180, 330, 80, 12),
                block("建筑面积", 40, 290, 80, 12),
                block("1000㎡", 180, 290, 80, 12)));

        assertThat(grid.getRowCount()).isEqualTo(2);
        assertThat(grid.getColumnCount()).isEqualTo(2);
        assertThat(grid.getCells()).hasSize(4);
        assertThat(cellAt(grid, 0, 0).getValue()).isEqualTo("单位名称");
        assertThat(cellAt(grid, 1, 1).getValue()).isEqualTo("1000㎡");
    }

    @Test
    void lowTextContainment_fallbackToCoordinateClustering() {
        // 图像格网有效但文字包住率 0.5 < 0.60 → 回退方式二（格网 2×2 而非 3×2）
        TableGrid grid = singleGrid(input(gridImage(),
                block("topL", 60, 66, 40, 12),
                block("topR", 150, 66, 40, 12),
                block("botL", 60, 30, 40, 12),
                block("botR", 150, 30, 40, 12)));

        assertThat(grid.getRowCount()).isEqualTo(2);
        assertThat(grid.getColumnCount()).isEqualTo(2);
        // 坐标聚类格网 bbox 来自文字行（x 60..190），而非图像格网（x 50..250）
        assertThat(grid.getBbox().getX()).isCloseTo(60f, within(0.6f));
        assertThat(grid.getBbox().getWidth()).isCloseTo(130f, within(0.6f));
        assertThat(cellAt(grid, 0, 0).getValue()).isEqualTo("topL");
        assertThat(cellAt(grid, 1, 1).getValue()).isEqualTo("botR");
    }

    // ---------- 值绑定（覆盖率主判定 + 中心点辅助 + 多行块完整绑定） ----------

    @Test
    void multilineBlock_bindsFullyToOneCell() {
        // 建设地点场景：两行文字块整体落于同一格 → 一格完整绑定不拆分
        TableGrid grid = singleGrid(input(gridImage(),
                block2("地点行一", 60, 180, "地点行二", 60, 160, 80, 12)));

        assertThat(grid.getCells()).hasSize(1);
        TableCell cell = grid.getCells().get(0);
        assertThat(cell.getRowIndex()).isEqualTo(1);
        assertThat(cell.getColumnIndex()).isZero();
        assertThat(cell.getValue()).isEqualTo("地点行一\n地点行二");
        assertThat(cell.getSource()).isEqualTo(ElementSource.PDF_TEXT);
    }

    @Test
    void centerAssist_bindsWhenCoverageBelowButCenterInside() {
        // 行块跨两格：右格覆盖率 0.583 < 0.60 但为最高覆盖且中心点在其内 → 辅助绑定
        TableGrid grid = singleGrid(input(gridImage(),
                block("跨格文字", 100, 290, 120, 12)));

        assertThat(grid.getCells()).hasSize(1);
        TableCell cell = grid.getCells().get(0);
        assertThat(cell.getColumnIndex()).isEqualTo(1);
        assertThat(cell.getValue()).isEqualTo("跨格文字");
    }

    @Test
    void noValues_returnsEmpty() {
        assertThat(recognizer().recognize(input(gridImage()))).isEmpty();
    }

    // ---------- 表头候选 / OCR / FUSION ----------

    @Test
    void headerOcr_fusionBinding_andTempFileDeleted() {
        // 顶左格绘制表头文字带（视觉特征达标）；值文字在顶右格
        BufferedImage image = gridImage();
        Graphics2D g = image.createGraphics();
        g.setColor(Color.BLACK);
        g.fillRect(60, 70, 40, 10);
        g.fillRect(60, 95, 40, 10);
        g.fillRect(60, 120, 40, 10);
        g.dispose();
        ocr.results.add(StubOcr.success("单位名称", 90f));

        TableGrid grid = singleGrid(input(image, block("XX公司", 170, 290, 60, 12)));

        assertThat(ocr.requests).hasSize(1);
        // 表头裁剪临时文件：调用时存在、用后即删
        assertThat(ocr.fileExistedDuringCall).isTrue();
        assertThat(ocr.files.get(0)).doesNotExist();

        assertThat(grid.getCells()).hasSize(2);
        TableCell headerCell = cellAt(grid, 0, 0);
        assertThat(headerCell.getHeader()).isEqualTo("单位名称");
        assertThat(headerCell.getValue()).isNull();
        assertThat(headerCell.getSource()).isEqualTo(ElementSource.OCR);
        assertThat(headerCell.getConfidence()).isCloseTo(0.9f, within(0.01f));

        TableCell valueCell = cellAt(grid, 0, 1);
        assertThat(valueCell.getValue()).isEqualTo("XX公司");
        // 表头-值绑定：header 沿袭 + source=FUSION + confidence=0.5+0.5×表头置信度
        assertThat(valueCell.getHeader()).isEqualTo("单位名称");
        assertThat(valueCell.getSource()).isEqualTo(ElementSource.FUSION);
        assertThat(valueCell.getConfidence()).isCloseTo(0.95f, within(0.01f));
    }

    @Test
    void headerOcrFailed_headerNull_valueStaysPdfText() {
        BufferedImage image = gridImage();
        Graphics2D g = image.createGraphics();
        g.setColor(Color.BLACK);
        g.fillRect(60, 70, 40, 10);
        g.fillRect(60, 95, 40, 10);
        g.fillRect(60, 120, 40, 10);
        g.dispose();
        ocr.results.add(StubOcr.failed());

        TableGrid grid = singleGrid(input(image, block("XX公司", 170, 290, 60, 12)));

        assertThat(ocr.requests).hasSize(1);
        // 表头格无值无 header 被过滤；值单元格保持 PDF_TEXT、header=null
        assertThat(grid.getCells()).hasSize(1);
        TableCell valueCell = grid.getCells().get(0);
        assertThat(valueCell.getValue()).isEqualTo("XX公司");
        assertThat(valueCell.getHeader()).isNull();
        assertThat(valueCell.getSource()).isEqualTo(ElementSource.PDF_TEXT);
        assertThat(valueCell.getConfidence()).isCloseTo(1.0f, within(0.01f));
    }

    @Test
    void headerOcrLowConfidence_headerNull_valueStaysPdfText() {
        BufferedImage image = gridImage();
        Graphics2D g = image.createGraphics();
        g.setColor(Color.BLACK);
        g.fillRect(60, 70, 40, 10);
        g.fillRect(60, 95, 40, 10);
        g.fillRect(60, 120, 40, 10);
        g.dispose();
        // SUCCESS 但置信度 0.30 < header-min-confidence(0.60)：
        // 低置信度不得强制修正，允许进入 fallback（header=null 优雅降级）
        ocr.results.add(StubOcr.success("模糊文字", 30f));

        TableGrid grid = singleGrid(input(image, block("XX公司", 170, 290, 60, 12)));

        assertThat(ocr.requests).hasSize(1);
        // 表头格无值无 header 被过滤；值单元格保持 PDF_TEXT、header=null
        assertThat(grid.getCells()).hasSize(1);
        TableCell valueCell = grid.getCells().get(0);
        assertThat(valueCell.getValue()).isEqualTo("XX公司");
        assertThat(valueCell.getHeader()).isNull();
        assertThat(valueCell.getSource()).isEqualTo(ElementSource.PDF_TEXT);
        assertThat(valueCell.getConfidence()).isCloseTo(1.0f, within(0.01f));
    }

    @Test
    void cropBeyondCell_ocrSkipped_headerNull() {
        // 桩：toPdfBox 回换框整体右移 20pt（模拟坐标变换口径不一致）。
        // 偏移量必须小于图宽余量：过大会令移位格的像素投影越出渲染图，
        // 使 analyzeCrop 越界而非走回验路径
        BufferedImage image = gridImage();
        Graphics2D g = image.createGraphics();
        g.setColor(Color.BLACK);
        // 墨迹带画在移位后 cell(0,1) 的像素投影 (170..270, 50..150) 内
        g.fillRect(180, 70, 40, 10);
        g.fillRect(180, 95, 40, 10);
        g.fillRect(180, 120, 40, 10);
        g.dispose();
        CoordinateMatcher inconsistent = new CoordinateMatcher(transformer) {
            @Override
            public BoundingBox toPdfBox(BoundingBox pixelBox, float dpi, float pageHeight) {
                BoundingBox pdf = super.toPdfBox(pixelBox, dpi, pageHeight);
                return BoundingBox.builder()
                        .x(pdf.getX() + 20f).y(pdf.getY())
                        .width(pdf.getWidth()).height(pdf.getHeight())
                        .build();
            }
        };

        // 值文字 (100..160, 290..302) 落在移位后 cell(0,0)=(70..170, 250..350) 内
        // → 文字包住率达标，图像格网仍被采用
        TableGrid grid = new HybridTableRecognizer(
                new ImageTableRecognizer(inconsistent),
                new CoordinateTableRecognizer(),
                new HeaderCandidateDetector(inconsistent),
                inconsistent,
                ocr)
                .recognize(input(image, block("XX公司", 100, 290, 60, 12)))
                .get(0);

        // 回验失败：OCR 未发起（不 OCR 超格内容）
        assertThat(ocr.requests).isEmpty();
        // 表头格被过滤，值格保持 PDF_TEXT、header=null（优雅降级）
        assertThat(grid.getCells()).hasSize(1);
        TableCell valueCell = grid.getCells().get(0);
        assertThat(valueCell.getValue()).isEqualTo("XX公司");
        assertThat(valueCell.getHeader()).isNull();
        assertThat(valueCell.getSource()).isEqualTo(ElementSource.PDF_TEXT);
    }

    // ---------- 异常封闭 ----------

    @Test
    void nullInput_exceptionClosed_empty() {
        assertThat(recognizer().recognize(null)).isEmpty();
        assertThat(recognizer().recognize(TableRecognitionInput.builder().pageNumber(1).build()))
                .isEmpty();
    }

    // ---------- 夹具 ----------

    private HybridTableRecognizer recognizer() {
        return new HybridTableRecognizer(
                new ImageTableRecognizer(matcher),
                new CoordinateTableRecognizer(),
                new HeaderCandidateDetector(matcher),
                matcher,
                ocr);
    }

    private TableGrid singleGrid(TableRecognitionInput input) {
        List<TableGrid> grids = recognizer().recognize(input);
        assertThat(grids).hasSize(1);
        return grids.get(0);
    }

    private TableRecognitionInput input(BufferedImage image, TextBlock... blocks) {
        return TableRecognitionInput.builder()
                .pageNumber(1)
                .renderedPage(image)
                .dpi(DPI)
                .pageWidth(IMG_W)
                .pageHeight(IMG_H)
                .textBlocks(List.of(blocks))
                .regions(List.of())
                .build();
    }

    private TableCell cellAt(TableGrid grid, int row, int col) {
        return grid.getCells().stream()
                .filter(c -> c.getRowIndex() == row && c.getColumnIndex() == col)
                .findFirst()
                .orElseThrow();
    }

    /**
     * 白底 + 1px 黑色表格线（竖线 x∈XS、横线 y∈YS；72dpi 下像素 = pt）。
     */
    private BufferedImage gridImage() {
        BufferedImage image = whiteImage();
        Graphics2D g = image.createGraphics();
        g.setColor(Color.BLACK);
        for (int x : XS) {
            g.drawLine(x, YS[0], x, YS[YS.length - 1]);
        }
        for (int y : YS) {
            g.drawLine(XS[0], y, XS[XS.length - 1], y);
        }
        g.dispose();
        return image;
    }

    private BufferedImage whiteImage() {
        BufferedImage image = new BufferedImage(IMG_W, IMG_H, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, IMG_W, IMG_H);
        g.dispose();
        return image;
    }

    private TextBlock block(String text, float x, float y, float w, float h) {
        TextLine line = TextLine.builder().text(text)
                .bbox(BoundingBox.builder().x(x).y(y).width(w).height(h).build())
                .fontSize(12f)
                .fontName("Helvetica")
                .build();
        return TextBlock.builder()
                .text(text)
                .lines(List.of(line))
                .bbox(line.getBbox())
                .fontSize(12f)
                .fontName("Helvetica")
                .page(1)
                .build();
    }

    /**
     * 两行文字块（bbox = 行框并集；块级整体绑定输入）。
     */
    private TextBlock block2(String text1, float x1, float y1,
                             String text2, float x2, float y2, float w, float h) {
        TextLine line1 = TextLine.builder().text(text1)
                .bbox(BoundingBox.builder().x(x1).y(y1).width(w).height(h).build())
                .fontSize(12f)
                .fontName("Helvetica")
                .build();
        TextLine line2 = TextLine.builder().text(text2)
                .bbox(BoundingBox.builder().x(x2).y(y2).width(w).height(h).build())
                .fontSize(12f)
                .fontName("Helvetica")
                .build();
        return TextBlock.builder()
                .text(text1 + "\n" + text2)
                .lines(List.of(line1, line2))
                .bbox(line1.getBbox().union(line2.getBbox()))
                .fontSize(12f)
                .fontName("Helvetica")
                .page(1)
                .build();
    }

    /**
     * OCR 桩：按队列返回预制结果并捕获请求（默认 EMPTY）。
     */
    private static final class StubOcr implements OcrParser {
        final Deque<OcrResult> results = new ArrayDeque<>();
        final List<OcrRequest> requests = new ArrayList<>();
        final List<File> files = new ArrayList<>();
        boolean fileExistedDuringCall;

        static OcrResult success(String text, float confidence) {
            OcrWord word = OcrWord.builder()
                    .text(text).x(0).y(0).width(10).height(10)
                    .confidence(confidence).page(1)
                    .source(ElementSource.OCR).lineNo(0)
                    .build();
            return OcrResult.builder()
                    .status(OcrStatus.SUCCESS)
                    .pages(List.of(OcrPage.builder().pageNumber(1).words(List.of(word)).build()))
                    .build();
        }

        static OcrResult failed() {
            return OcrResult.builder()
                    .status(OcrStatus.FAILED)
                    .errorMessage("引擎缺失")
                    .pages(List.of())
                    .build();
        }

        @Override
        public OcrResult recognize(OcrRequest request) {
            requests.add(request);
            files.add(request.getImageFile());
            fileExistedDuringCall = request.getImageFile().exists();
            return results.isEmpty()
                    ? OcrResult.builder().status(OcrStatus.EMPTY).pages(List.of()).build()
                    : results.poll();
        }
    }
}
