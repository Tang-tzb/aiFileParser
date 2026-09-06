package com.aifp.aiagent.parser.pdf.page;

import com.aifp.aiagent.common.ResultCode;
import com.aifp.aiagent.exception.BusinessException;
import com.aifp.aiagent.parser.ocr.*;
import com.aifp.aiagent.parser.pdf.PageContentType;
import com.aifp.aiagent.parser.pdf.PageImageRenderer;
import com.aifp.aiagent.parser.pdf.PageProfile;
import com.aifp.aiagent.parser.pdf.text.BoundingBox;
import com.aifp.aiagent.parser.pdf.text.SimpleCoordinateTransformer;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.*;

/**
 * {@link ImagePageParser} 测试：OCR 结果 → PageDocument 映射（三态分支）、
 * 坐标换算、失败明确报错，以及真机引擎全链路（条件启用）。
 *
 * @author Tang_tzb
 */
class ImagePageParserTest {

    private static final float PAGE_W = PdfPageTestSupport.PAGE_W;
    private static final float PAGE_H = PdfPageTestSupport.PAGE_H;

    @TempDir
    Path tempDir;

    private static OcrWord word(String text, int x, int y, int w, int h, int lineNo) {
        return OcrWord.builder()
                .text(text).x(x).y(y).width(w).height(h)
                .confidence(95f).page(1)
                .source(ElementSource.OCR)
                .lineNo(lineNo)
                .build();
    }

    /**
     * 探测真机引擎：OCR_TESSERACT_PATH 环境变量或 PATH 中的 tesseract，
     * 且已安装 chi_sim 语言包；不可用返回 null。
     */
    private static String resolveEngineCommand() {
        String candidate = System.getenv("OCR_TESSERACT_PATH");
        if (candidate == null || candidate.isBlank()) {
            candidate = "tesseract";
        }
        try {
            Process process = new ProcessBuilder(candidate, "--list-langs").start();
            String output = new String(process.getInputStream().readAllBytes());
            boolean ok = process.waitFor(30, java.util.concurrent.TimeUnit.SECONDS)
                    && process.exitValue() == 0;
            return ok && output.contains("chi_sim") ? candidate : null;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 生成"整页扫描件 + 可识别文字"的 PDF：白底图片上绘制中英文文本。
     */
    private static void buildScannedTextPdf(File pdf) throws Exception {
        BufferedImage image = new BufferedImage(1240, 1754, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, image.getWidth(), image.getHeight());
        g.setColor(Color.BLACK);
        g.setFont(new Font("SimSun", Font.PLAIN, 60));
        g.drawString("施工许可证", 120, 200);
        g.drawString("编号 4401-2026", 120, 350);
        g.dispose();

        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage(new PDRectangle(PAGE_W, PAGE_H));
            doc.addPage(page);
            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                cs.drawImage(LosslessFactory.createFromImage(doc, image), 0, 0, PAGE_W, PAGE_H);
            }
            doc.save(pdf);
        }
    }

    /**
     * 构造注入桩 OCR 的解析器，并捕获收到的 OcrRequest（校验请求元数据）。
     */
    private ImagePageParser parserWith(OcrParser stub, AtomicReference<OcrRequest> captured) {
        OcrParser wrapper = request -> {
            if (captured != null) {
                captured.set(request);
            }
            return stub.recognize(request);
        };
        return new ImagePageParser(new PageImageRenderer(), wrapper, new SimpleCoordinateTransformer());
    }

    private PageContext imageOnlyContext(PDDocument doc) {
        PageProfile profile = PageProfile.builder()
                .pageNumber(1)
                .contentType(PageContentType.IMAGE_ONLY)
                .pageWidth(PAGE_W)
                .pageHeight(PAGE_H)
                .imageCount(1)
                .hasFullPageImage(true)
                .build();
        return PageContext.builder().document(doc).pageIndex(0).profile(profile).build();
    }

    @Test
    void parse_success_mapsLinesToTextElementsWithUserSpaceBbox() throws Exception {
        File pdf = tempDir.resolve("scan.pdf").toFile();
        PdfPageTestSupport.buildImagePagePdf(pdf);

        OcrParser stub = request -> {
            // dpi=200 → scale=0.36；两行三词（lineNo 0,0,1）
            OcrPage page = OcrPage.builder()
                    .pageNumber(request.getPageNumber())
                    .imageWidth(request.getImageWidth())
                    .imageHeight(request.getImageHeight())
                    .dpi(request.getDpi())
                    .words(List.of(
                            word("施工", 100, 100, 200, 40, 0),
                            word("许可证", 320, 100, 100, 40, 0),
                            word("编号", 100, 200, 150, 40, 1)))
                    .build();
            return OcrResult.builder().status(OcrStatus.SUCCESS).pages(List.of(page)).build();
        };
        AtomicReference<OcrRequest> captured = new AtomicReference<>();
        ImagePageParser parser = parserWith(stub, captured);

        try (PDDocument doc = Loader.loadPDF(pdf)) {
            PageDocument result = parser.parse(imageOnlyContext(doc));

            assertThat(result.getParserName()).isEqualTo("ImagePageParser");
            assertThat(result.getContentType()).isEqualTo(PageContentType.IMAGE_ONLY);

            assertThat(result.getElements()).hasSize(2);
            PageElement line1 = result.getElements().get(0);
            assertThat(line1.getType()).isEqualTo(PageElementType.TEXT);
            assertThat(line1.getSource()).isEqualTo(ElementSource.OCR);
            assertThat(line1.getText()).isEqualTo("施工 许可证");
            assertThat(line1.getFontSize()).isNull();
            assertThat(line1.getFontName()).isNull();

            // 行 bbox = 词框换算（像素→用户空间）后并集：x=36, y=791.6, w=115.2, h=14.4
            BoundingBox bbox1 = line1.getBbox();
            assertThat(bbox1.getX()).isEqualTo(36f, within(0.001f));
            assertThat(bbox1.getY()).isEqualTo(791.6f, within(0.001f));
            assertThat(bbox1.getWidth()).isEqualTo(115.2f, within(0.001f));
            assertThat(bbox1.getHeight()).isEqualTo(14.4f, within(0.001f));

            // 第 2 行：y = 842 − (200+40)×0.36 = 755.6
            PageElement line2 = result.getElements().get(1);
            assertThat(line2.getText()).isEqualTo("编号");
            assertThat(line2.getBbox().getY()).isEqualTo(755.6f, within(0.001f));

            // 请求元数据由渲染方填充（识别层无需二次解码）
            OcrRequest request = captured.get();
            assertThat(request.getPageNumber()).isEqualTo(1);
            assertThat(request.getDpi()).isEqualTo(200);
            assertThat(request.getImageWidth()).isGreaterThan(0);
            assertThat(request.getImageFile()).doesNotExist();
        }
    }

    @Test
    void parse_failedStatus_throwsExplicitOcrError() throws Exception {
        File pdf = tempDir.resolve("scan-failed.pdf").toFile();
        PdfPageTestSupport.buildImagePagePdf(pdf);

        OcrParser stub = request -> OcrResult.builder()
                .status(OcrStatus.FAILED)
                .errorMessage("Tesseract 未安装或路径未配置")
                .pages(List.of())
                .build();
        ImagePageParser parser = parserWith(stub, null);

        try (PDDocument doc = Loader.loadPDF(pdf)) {
            assertThatThrownBy(() -> parser.parse(imageOnlyContext(doc)))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("未安装或路径未配置")
                    .extracting(e -> ((BusinessException) e).getCode())
                    .isEqualTo(ResultCode.FILE_OCR_ERROR.getCode());
        }
    }

    @Test
    void parse_emptyStatus_returnsEmptyElementsWithoutThrowing() throws Exception {
        File pdf = tempDir.resolve("scan-blank.pdf").toFile();
        PdfPageTestSupport.buildImagePagePdf(pdf);

        OcrParser stub = request -> OcrResult.builder()
                .status(OcrStatus.EMPTY).pages(List.of()).build();
        ImagePageParser parser = parserWith(stub, null);

        try (PDDocument doc = Loader.loadPDF(pdf)) {
            PageDocument result = parser.parse(imageOnlyContext(doc));
            assertThat(result.getElements()).isEmpty();
            assertThat(result.getParserName()).isEqualTo("ImagePageParser");
        }
    }

    /**
     * 真机全链路验收（引擎 + chi_sim 可用时启用，离线自动跳过）：
     * 程序化整页扫描 PDF → 渲染 → Tesseract TSV → OcrWord → PageDocument。
     */
    @Test
    void parse_realEngine_scannedPdfProducesOcrElements() throws Exception {
        String engineCommand = resolveEngineCommand();
        org.junit.jupiter.api.Assumptions.assumeTrue(engineCommand != null,
                "Tesseract + chi_sim 未安装，真机 OCR 测试跳过");

        File pdf = tempDir.resolve("scan-real.pdf").toFile();
        buildScannedTextPdf(pdf);

        TesseractOcrParser ocrParser = new TesseractOcrParser();
        org.springframework.test.util.ReflectionTestUtils.setField(ocrParser, "tesseractPath", engineCommand);
        ImagePageParser parser = new ImagePageParser(
                new PageImageRenderer(), ocrParser, new SimpleCoordinateTransformer());

        try (PDDocument doc = Loader.loadPDF(pdf)) {
            PageDocument result = parser.parse(imageOnlyContext(doc));

            assertThat(result.getParserName()).isEqualTo("ImagePageParser");
            assertThat(result.getElements()).isNotEmpty();
            for (PageElement element : result.getElements()) {
                assertThat(element.getType()).isEqualTo(PageElementType.TEXT);
                assertThat(element.getSource()).isEqualTo(ElementSource.OCR);
                assertThat(element.getText()).isNotBlank();
                assertThat(element.getBbox()).isNotNull();
                // PDF 用户空间值域：y ≥ 0 且框在页面范围内
                assertThat(element.getBbox().getY()).isGreaterThanOrEqualTo(0f);
                assertThat(element.getBbox().top()).isLessThanOrEqualTo(PAGE_H + 0.5f);
            }
        }
    }
}
