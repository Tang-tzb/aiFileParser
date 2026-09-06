package com.aifp.aiagent.parser.pdf.page;

import com.aifp.aiagent.common.ResultCode;
import com.aifp.aiagent.exception.BusinessException;
import com.aifp.aiagent.parser.ocr.*;
import com.aifp.aiagent.parser.pdf.PageContentType;
import com.aifp.aiagent.parser.pdf.PageProfile;
import com.aifp.aiagent.parser.pdf.region.OcrEligibilityEvaluator;
import com.aifp.aiagent.parser.pdf.region.RegionAnalyzer;
import com.aifp.aiagent.parser.pdf.region.RegionType;
import com.aifp.aiagent.parser.pdf.region.VisualRegion;
import com.aifp.aiagent.parser.pdf.text.*;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

import static org.assertj.core.api.Assertions.*;

/**
 * {@link MixedPageParser} 阶段 6 融合流程测试（v2 验收 9 点）：
 * ① OCR 使用裁剪图 ② 不接收整页图片 ③ STAMP 不 OCR ④ SIGNATURE 不 OCR
 * ⑤ IMAGE 默认不 OCR ⑥ TABLE 可以 OCR ⑦ OCR bbox 落在对应 Region 内（含容差）
 * ⑧ PDF_TEXT 原生文字保留 ⑨ 重复 OCR Word 被剔除；另：OCR FAILED → BusinessException。
 *
 * @author Tang_tzb
 */
class MixedPageParserTest {

    private static final float PAGE_W = PdfPageTestSupport.PAGE_W;
    private static final float PAGE_H = PdfPageTestSupport.PAGE_H;
    private static final float OCR_DPI = 200f;

    @TempDir
    Path tempDir;

    // ---------- 测试桩 ----------
    private final CoordinateTransformer transformer = new SimpleCoordinateTransformer();
    private CapturingOcr ocr = new CapturingOcr();

    /**
     * 构建注入桩区域分析器的解析器（区域序列 = 给定 regions，绕过像素分类，
     * 使 OCR 路由决策可精确断言；真实区域分析由 DefaultRegionAnalyzerTest 覆盖）。
     */
    private MixedPageParser parserWithRegions(VisualRegion... regions) {
        RegionAnalyzer stubAnalyzer = (doc, idx, blocks, image, dpi) -> List.of(regions);
        return new MixedPageParser(
                new DefaultPdfTextExtractor(new PdfCoordinateConverter()),
                new com.aifp.aiagent.parser.pdf.PageImageRenderer(),
                ocr,
                stubAnalyzer,
                new OcrEligibilityEvaluator(),
                new com.aifp.aiagent.parser.pdf.region.CoordinateMatcher(transformer));
    }

    private VisualRegion region(RegionType type, BoundingBox bbox, boolean likelyTable,
                                double coverage, double candidate) {
        return VisualRegion.builder()
                .regionType(type)
                .bbox(bbox)
                .likelyTable(likelyTable)
                .tableScore(likelyTable ? 0.5 : 0)
                .coverageRatio(coverage)
                .textCandidateScore(candidate)
                .pageAreaRatio((double) bbox.getWidth() * bbox.getHeight() / (PAGE_W * PAGE_H))
                .build();
    }

    private OcrWord word(String text, int x, int y, int w, int h, int lineNo) {
        return OcrWord.builder()
                .text(text).x(x).y(y).width(w).height(h)
                .confidence(90f).page(1).source(ElementSource.OCR).lineNo(lineNo)
                .build();
    }

    private PageContext context(PDDocument doc) {
        PageProfile profile = PageProfile.builder()
                .pageNumber(1)
                .contentType(PageContentType.MIXED)
                .pageWidth(PAGE_W)
                .pageHeight(PAGE_H)
                .imageCount(1)
                .hasFullPageImage(true)
                .textCount(30)
                .build();
        return PageContext.builder().document(doc).pageIndex(0).profile(profile).build();
    }

    private PageDocument parse(File pdf, MixedPageParser parser) throws Exception {
        try (PDDocument doc = Loader.loadPDF(pdf)) {
            return parser.parse(context(doc));
        }
    }

    @Test
    void ocrInput_isRegionCrop_notFullPage() throws Exception {
        File pdf = tempDir.resolve("m1.pdf").toFile();
        PdfPageTestSupport.buildTextPagePdf(pdf);
        // 200×150pt 区域 @200DPI → 裁剪图约 557×417px（远小于整页 1653×2339px）
        VisualRegion tableRegion = region(RegionType.TABLE,
                BoundingBox.builder().x(100).y(400).width(200).height(150).build(), false, 0, 0);
        ocr.results.add(CapturingOcr.empty());

        PageDocument result = parse(pdf, parserWithRegions(tableRegion));

        assertThat(ocr.requests).hasSize(1);
        OcrRequest request = ocr.requests.get(0);
        assertThat(request.getDpi()).isEqualTo((int) OCR_DPI);
        assertThat(request.getImageWidth()).isBetween(555, 559);
        assertThat(request.getImageHeight()).isBetween(415, 419);
        assertThat(request.getImageWidth()).isLessThan((int) (PAGE_W * OCR_DPI / 72));
        assertThat(request.getImageHeight()).isLessThan((int) (PAGE_H * OCR_DPI / 72));
        // 裁剪临时文件：调用时存在，解析结束后即删
        assertThat(ocr.cropFileExistedDuringCall).isTrue();
        assertThat(request.getImageFile()).doesNotExist();
    }

    // ---------- ①②：OCR 输入必须是区域裁剪图 ----------

    @Test
    void fullPageImageRegion_exemptFromOcr_realChain() throws Exception {
        // 真实分析链路：整页图 + 文字（MIXED 夹具）→ 整页图豁免，OCR 恒不触发
        File pdf = tempDir.resolve("m2.pdf").toFile();
        PdfPageTestSupport.buildMixedPagePdf(pdf);
        MixedPageParser parser = PdfPageTestSupport.buildMixedPageParser(ocr);

        PageDocument result = parse(pdf, parser);

        assertThat(ocr.requests).isEmpty();
        assertThat(result.getParserName()).isEqualTo("MixedPageParser");
        assertThat(result.getContentType()).isEqualTo(PageContentType.MIXED);
        List<PageElement> regionElements = result.getElements().stream()
                .filter(e -> e.getType() == PageElementType.IMAGE_REGION)
                .toList();
        assertThat(regionElements).hasSize(1);
        assertThat(regionElements.get(0).getRegionType()).isEqualTo(RegionType.IMAGE);
        // 区域溯源 metadata（整页图占位比远超豁免阈值 0.85，经评估器豁免 OCR）
        assertThat(regionElements.get(0).getDescription()).contains("likelyTable=false");
    }

    @Test
    void stampRegion_notOcr() throws Exception {
        File pdf = tempDir.resolve("m3.pdf").toFile();
        PdfPageTestSupport.buildTextPagePdf(pdf);
        PageDocument result = parse(pdf, parserWithRegions(region(RegionType.STAMP,
                BoundingBox.builder().x(400).y(600).width(80).height(80).build(), false, 0, 0)));

        assertThat(ocr.requests).isEmpty();
        assertThat(regionTypesOf(result)).containsExactly(RegionType.STAMP);
    }

    // ---------- ③④⑤：默认不 OCR 的区域类型 ----------

    @Test
    void signatureRegion_notOcr() throws Exception {
        File pdf = tempDir.resolve("m4.pdf").toFile();
        PdfPageTestSupport.buildTextPagePdf(pdf);
        PageDocument result = parse(pdf, parserWithRegions(region(RegionType.SIGNATURE,
                BoundingBox.builder().x(100).y(80).width(150).height(60).build(), false, 0, 0)));

        assertThat(ocr.requests).isEmpty();
        assertThat(regionTypesOf(result)).containsExactly(RegionType.SIGNATURE);
    }

    @Test
    void imageRegion_defaultNotOcr() throws Exception {
        File pdf = tempDir.resolve("m5.pdf").toFile();
        PdfPageTestSupport.buildTextPagePdf(pdf);
        // 普通 IMAGE（无视觉文本候选）→ 默认不 OCR；覆盖率低也不构成理由
        PageDocument result = parse(pdf, parserWithRegions(region(RegionType.IMAGE,
                BoundingBox.builder().x(200).y(300).width(120).height(90).build(), false, 0.05, 0.1)));

        assertThat(ocr.requests).isEmpty();
        assertThat(regionTypesOf(result)).containsExactly(RegionType.IMAGE);
    }

    @Test
    void tableRegion_ocrAllowed_andMetadataPropagated() throws Exception {
        File pdf = tempDir.resolve("m6.pdf").toFile();
        PdfPageTestSupport.buildTextPagePdf(pdf);
        ocr.results.add(CapturingOcr.empty());

        PageDocument result = parse(pdf, parserWithRegions(region(RegionType.TABLE,
                BoundingBox.builder().x(100).y(400).width(200).height(150).build(), true, 0, 0)));

        assertThat(ocr.requests).hasSize(1);
        List<PageElement> regionElements = result.getElements().stream()
                .filter(e -> e.getType() == PageElementType.IMAGE_REGION)
                .toList();
        assertThat(regionElements).hasSize(1);
        assertThat(regionElements.get(0).getRegionType()).isEqualTo(RegionType.TABLE);
        // likelyTable 候选信息传递（阶段 7 消费），但仅为标记而非表格识别完成
        assertThat(regionElements.get(0).getDescription()).contains("likelyTable=true");
    }

    // ---------- ⑥：TABLE / 表格候选可以区域 OCR ----------

    @Test
    void ocrElements_mustFallWithinRegion() throws Exception {
        File pdf = tempDir.resolve("m7.pdf").toFile();
        PdfPageTestSupport.buildTextPagePdf(pdf);
        // 裁剪图 (277, 811, 557×417)px：合法词 (10,10)；非法词 x=-2000（远出区域）
        ocr.results.add(CapturingOcr.success(
                word("Hello", 10, 10, 100, 20, 0),
                word("GHOST", -2000, 10, 100, 20, 1)));

        PageDocument result = parse(pdf, parserWithRegions(region(RegionType.TABLE,
                BoundingBox.builder().x(100).y(400).width(200).height(150).build(), false, 0, 0)));

        List<PageElement> ocrElements = result.getElements().stream()
                .filter(e -> e.getSource() == ElementSource.OCR)
                .toList();
        assertThat(ocrElements).hasSize(1);
        PageElement element = ocrElements.get(0);
        // 词 bbox = 裁剪偏移 + 词坐标 → PDF 用户空间（Y 轴翻转）
        BoundingBox bbox = element.getBbox();
        assertThat(bbox.getX()).isCloseTo((277 + 10) * 72f / OCR_DPI, within(0.1f));
        assertThat(bbox.getY()).isCloseTo(PAGE_H - (811 + 10 + 20) * 72f / OCR_DPI, within(0.1f));
        // 合法性：与区域（外扩容差）存在有效交集
        BoundingBox expanded = region0().expand(2f);
        assertThat(bbox.intersection(expanded)).isNotNull();
        assertThat(element.getRegionType()).isEqualTo(RegionType.TABLE);
        assertThat(element.getConfidence()).isCloseTo(90f, within(0.1f));
        assertThat(element.getFontSize()).isNull();
    }

    // ---------- ⑦⑧⑨：合法性校验 / 原生文字保留 / 去重 ----------

    @Test
    void pdfNativeText_preserved() throws Exception {
        File pdf = tempDir.resolve("m8.pdf").toFile();
        PdfPageTestSupport.buildMixedPagePdf(pdf);
        MixedPageParser parser = PdfPageTestSupport.buildMixedPageParser(ocr);

        PageDocument result = parse(pdf, parser);

        List<PageElement> pdfTextElements = result.getElements().stream()
                .filter(e -> e.getSource() == ElementSource.PDF_TEXT)
                .toList();
        assertThat(pdfTextElements).hasSize(1);
        PageElement textElement = pdfTextElements.get(0);
        assertThat(textElement.getType()).isEqualTo(PageElementType.TEXT);
        assertThat(textElement.getText()).contains("Hello PDFBox page parser fixture");
        assertThat(textElement.getBbox()).isNotNull();
        assertThat(textElement.getFontSize()).isCloseTo(12f, within(0.1f));
        assertThat(textElement.getRegionType()).isNull();
        assertThat(textElement.getConfidence()).isNull();
    }

    @Test
    void duplicateOcrWord_overNativeText_removed() throws Exception {
        File pdf = tempDir.resolve("m9.pdf").toFile();
        PdfPageTestSupport.buildTextPagePdf(pdf);
        // 原生文字 bbox → 构造覆盖它的 TABLE 区域与 OCR 词（裁剪图坐标系）
        BoundingBox textBbox;
        try (PDDocument doc = Loader.loadPDF(pdf)) {
            textBbox = new DefaultPdfTextExtractor(new PdfCoordinateConverter())
                    .extract(doc, 0).getBlocks().get(0).getBbox();
        }
        BoundingBox regionBox = textBbox.expand(20f);
        float k = OCR_DPI / 72f;
        int cropX = (int) Math.floor(regionBox.getX() * k);
        int cropY = (int) Math.floor((PAGE_H - regionBox.top()) * k);
        int wx = Math.round(textBbox.getX() * k) - cropX;
        int wy = Math.round((PAGE_H - textBbox.top()) * k) - cropY;
        ocr.results.add(CapturingOcr.success(
                word("Hello", wx, wy, Math.round(textBbox.getWidth() * k),
                        Math.round(textBbox.getHeight() * k), 0)));

        PageDocument result = parse(pdf, parserWithRegions(region(RegionType.TABLE,
                regionBox, false, 0, 0)));

        // 词面积几乎全部被 PDF 原生文字覆盖（主判定 ≥0.80）→ 剔除
        assertThat(ocr.requests).hasSize(1);
        assertThat(result.getElements().stream()
                .filter(e -> e.getSource() == ElementSource.OCR).toList()).isEmpty();
        assertThat(result.getElements().stream()
                .filter(e -> e.getSource() == ElementSource.PDF_TEXT).toList()).isNotEmpty();
    }

    @Test
    void regionOcrFailed_throwsBusinessException() throws Exception {
        File pdf = tempDir.resolve("m10.pdf").toFile();
        PdfPageTestSupport.buildTextPagePdf(pdf);
        ocr.results.add(OcrResult.builder()
                .status(OcrStatus.FAILED).errorMessage("引擎崩溃").pages(List.of()).build());

        MixedPageParser parser = parserWithRegions(region(RegionType.TABLE,
                BoundingBox.builder().x(100).y(400).width(200).height(150).build(), false, 0, 0));
        try (PDDocument doc = Loader.loadPDF(pdf)) {
            assertThatThrownBy(() -> parser.parse(context(doc)))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getCode())
                    .isEqualTo(ResultCode.FILE_OCR_ERROR.getCode());
        }
    }

    // ---------- 失败语义 ----------

    private BoundingBox region0() {
        return BoundingBox.builder().x(100).y(400).width(200).height(150).build();
    }

    // ---------- 辅助 ----------

    private List<RegionType> regionTypesOf(PageDocument result) {
        return result.getElements().stream()
                .filter(e -> e.getType() == PageElementType.IMAGE_REGION)
                .map(PageElement::getRegionType)
                .toList();
    }

    /**
     * 捕获 OCR 请求并按队列返回预制结果（同时记录调用时裁剪文件是否真实存在）。
     */
    private static final class CapturingOcr implements OcrParser {
        final List<OcrRequest> requests = new ArrayList<>();
        final Deque<OcrResult> results = new ArrayDeque<>();
        boolean cropFileExistedDuringCall;

        static OcrResult empty() {
            return OcrResult.builder().status(OcrStatus.EMPTY).pages(List.of()).build();
        }

        static OcrResult success(OcrWord... words) {
            OcrPage page = OcrPage.builder()
                    .pageNumber(1)
                    .words(List.of(words))
                    .build();
            return OcrResult.builder().status(OcrStatus.SUCCESS).pages(List.of(page)).build();
        }

        @Override
        public OcrResult recognize(OcrRequest request) {
            requests.add(request);
            cropFileExistedDuringCall = request.getImageFile().exists();
            return results.isEmpty() ? empty() : results.poll();
        }
    }
}
