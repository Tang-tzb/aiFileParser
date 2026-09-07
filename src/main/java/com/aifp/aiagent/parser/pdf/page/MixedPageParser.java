package com.aifp.aiagent.parser.pdf.page;

import com.aifp.aiagent.common.ResultCode;
import com.aifp.aiagent.exception.BusinessException;
import com.aifp.aiagent.parser.ocr.*;
import com.aifp.aiagent.parser.pdf.PageContentType;
import com.aifp.aiagent.parser.pdf.PageImageRenderer;
import com.aifp.aiagent.parser.pdf.PageProfile;
import com.aifp.aiagent.parser.pdf.layout.TableGrid;
import com.aifp.aiagent.parser.pdf.layout.TableRecognitionInput;
import com.aifp.aiagent.parser.pdf.layout.TableStructureRecognizer;
import com.aifp.aiagent.parser.pdf.region.*;
import com.aifp.aiagent.parser.pdf.text.BoundingBox;
import com.aifp.aiagent.parser.pdf.text.PdfText;
import com.aifp.aiagent.parser.pdf.text.PdfTextExtractor;
import com.aifp.aiagent.parser.pdf.text.TextBlock;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 图文混合页解析器（MIXED）：PDF 原生文字 + 视觉区域统一空间融合（阶段 6 核心）。
 * <p>
 * 最终链路：PDFBox TextBlock → RegionAnalyzer → OcrEligibilityEvaluator →
 * 区域级 OCR（裁剪图）→ CoordinateTransformer → 词去重 → 空间合法性校验 → PageDocument。
 * <p>
 * 关键原则（v2 冻结）：
 * <ul>
 *   <li>PDF 原生文字恒由 PDFBox 提供（文字层单一来源）；</li>
 *   <li>禁止整页 OCR：OCR 输入恒为视觉区域裁剪图，且整页图区域经
 *       {@link OcrEligibilityEvaluator} 豁免；</li>
 *   <li>TEXT/STAMP/SIGNATURE/普通 IMAGE 默认不进正文 OCR，仅 TABLE/表格候选
 *       及存在明显视觉文本候选的区域允许区域 OCR；</li>
 *   <li>区域元素（IMAGE_REGION）恒产出：溯源与阶段 7/8 表格结构/表头融合消费；
 *       likelyTable 仅为候选标记，不代表表格识别完成；</li>
 *   <li>OCR 词去重主判定 = 词面积被 PDF 原生文字覆盖比例（非 IoU，见
 *       {@link CoordinateMatcher}）；不合法元素剔除并告警，不中断解析。</li>
 * </ul>
 * 性能边界（2CPU/8GB）：单页一次渲染；OCR 并发 1（引擎层信号量）；
 * 渲染图与区域裁剪图临时文件即用即删。
 *
 * @author Tang_tzb
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MixedPageParser implements PageParser {

    /**
     * 解析器名（写入 PageDocument.parserName，供路由验收与溯源）
     */
    static final String PARSER_NAME = "MixedPageParser";

    private final PdfTextExtractor textExtractor;
    private final PageImageRenderer pageImageRenderer;
    private final OcrParser ocrParser;
    private final RegionAnalyzer regionAnalyzer;
    private final OcrEligibilityEvaluator eligibilityEvaluator;
    private final CoordinateMatcher coordinateMatcher;
    private final TableStructureRecognizer tableStructureRecognizer;

    @Override
    public PageContentType supportedType() {
        return PageContentType.MIXED;
    }

    @Override
    public PageDocument parse(PageContext context) {
        PageProfile profile = context.getProfile();

        // 1. PDF 原生文字（文字层单一来源，TEXT 元素原样保留结构化信息）
        PdfText text = textExtractor.extract(context.getDocument(), context.getPageIndex());
        List<TextBlock> textBlocks = text.getBlocks();

        // 2. 单页一次渲染 → 区域分析 → 区域级 OCR 融合 → 表格结构恢复
        PageImageRenderer.RenderedPage rendered = null;
        List<PageElement> ocrElements = new ArrayList<>();
        List<PageElement> regionElements = new ArrayList<>();
        List<TableGrid> tables = List.of();
        try {
            rendered = pageImageRenderer.renderToTempPng(context.getDocument(), context.getPageIndex());
            List<VisualRegion> regions = regionAnalyzer.analyze(context.getDocument(),
                    context.getPageIndex(), textBlocks, rendered.getImage(), rendered.getDpi());
            for (VisualRegion region : regions) {
                // 区域元素恒产出（溯源与阶段 7/8 消费）
                regionElements.add(buildRegionElement(region));
                if (eligibilityEvaluator.shouldOcr(region)) {
                    ocrElements.addAll(ocrRegion(region, rendered, context, textBlocks));
                }
            }
            // 阶段 7：表格结构恢复（渲染临时文件删除之前，复用同一份渲染图）
            tables = recognizeTables(regions, rendered, context, textBlocks);
        } catch (IOException e) {
            log.error("页面渲染失败 pageIndex={}", context.getPageIndex(), e);
            throw new BusinessException(ResultCode.FILE_PARSE_ERROR,
                    "页面渲染失败: 第" + context.getPageNumber() + "页");
        } finally {
            deleteQuietly(rendered == null ? null : rendered.getFile());
        }

        return buildDocument(context, profile, fuseElements(mapTextElements(textBlocks),
                ocrElements, regionElements), tables);
    }

    // ---------- 表格结构恢复（阶段 7） ----------

    /**
     * 表格结构恢复：存在 TABLE/likelyTable 区域时触发识别（渲染临时文件删除
     * 之前执行，复用同一份渲染图，单页仅渲染一次的约束不变）。
     * 失败语义：识别器内部全异常封闭；此处再兜底捕获，降级为空 tables 不中断解析。
     */
    private List<TableGrid> recognizeTables(List<VisualRegion> regions,
                                            PageImageRenderer.RenderedPage rendered,
                                            PageContext context, List<TextBlock> textBlocks) {
        boolean hasTableCandidate = regions.stream()
                .anyMatch(region -> region.getRegionType() == RegionType.TABLE || region.isLikelyTable());
        if (!hasTableCandidate) {
            return List.of();
        }
        try {
            return tableStructureRecognizer.recognize(TableRecognitionInput.builder()
                    .pageNumber(context.getPageNumber())
                    .renderedPage(rendered.getImage())
                    .dpi(rendered.getDpi())
                    .pageWidth(context.getProfile().getPageWidth())
                    .pageHeight(context.getProfile().getPageHeight())
                    .textBlocks(textBlocks)
                    .regions(regions)
                    .build());
        } catch (Exception e) {
            log.warn("表格结构识别降级为空 pageNumber={}", context.getPageNumber(), e);
            return List.of();
        }
    }

    // ---------- 文字层映射 ----------

    /**
     * PDF 原生文字块 → TEXT 元素（source=PDF_TEXT，text/bbox/fontSize/fontName 原样保留）。
     */
    private List<PageElement> mapTextElements(List<TextBlock> textBlocks) {
        List<PageElement> elements = new ArrayList<>(textBlocks.size());
        for (TextBlock block : textBlocks) {
            elements.add(PageElement.builder()
                    .type(PageElementType.TEXT)
                    .source(ElementSource.PDF_TEXT)
                    .text(block.getText())
                    .bbox(block.getBbox())
                    .fontSize(block.getFontSize())
                    .fontName(block.getFontName())
                    .build());
        }
        return elements;
    }

    // ---------- 区域级 OCR ----------

    /**
     * 区域 OCR 主流程：裁剪视觉区域（临时 PNG 即用即删）→ 识别 → 词级
     * 去重与空间合法性过滤 → 行分组 TEXT 元素。
     * 失败语义：识别 FAILED → FILE_OCR_ERROR（与 IMAGE_ONLY 同契约）。
     */
    private List<PageElement> ocrRegion(VisualRegion region, PageImageRenderer.RenderedPage rendered,
                                        PageContext context, List<TextBlock> textBlocks) {
        float pageHeight = context.getProfile().getPageHeight();
        int[] crop = coordinateMatcher.pixelCropBounds(region.getBbox(), rendered.getDpi(),
                pageHeight, rendered.getWidth(), rendered.getHeight());
        File cropFile = null;
        try {
            cropFile = writeCropTempPng(rendered.getImage(), crop);
            OcrResult result = ocrParser.recognize(OcrRequest.builder()
                    .imageFile(cropFile)
                    .pageNumber(context.getPageNumber())
                    .imageWidth(crop[2])
                    .imageHeight(crop[3])
                    .dpi(rendered.getDpi())
                    .build());
            if (result.getStatus() == OcrStatus.FAILED) {
                throw new BusinessException(ResultCode.FILE_OCR_ERROR, result.getErrorMessage());
            }
            if (result.getStatus() == OcrStatus.EMPTY) {
                log.info("区域 OCR 未识别到文字 pageNumber={}, regionType={}",
                        context.getPageNumber(), region.getRegionType());
                return List.of();
            }
            return mapRegionLines(result, region, crop, rendered.getDpi(), pageHeight,
                    context, textBlocks);
        } catch (IOException e) {
            log.error("区域裁剪失败 pageNumber={}, region={}", context.getPageNumber(), region.getBbox(), e);
            throw new BusinessException(ResultCode.FILE_PARSE_ERROR,
                    "区域裁剪失败: 第" + context.getPageNumber() + "页");
        } finally {
            deleteQuietly(cropFile);
        }
    }

    /**
     * 写出区域裁剪临时 PNG（getSubimage 共享缓冲，写出即释放）。
     */
    private File writeCropTempPng(BufferedImage image, int[] crop) throws IOException {
        BufferedImage subImage = image.getSubimage(crop[0], crop[1], crop[2], crop[3]);
        File tempFile = Files.createTempFile("aifp-region-ocr-", ".png").toFile();
        ImageIO.write(subImage, "png", tempFile);
        return tempFile;
    }

    /**
     * OCR 词 → 行元素：去重（主判定 = 词面积被 PDF 文字覆盖比例）→
     * 空间合法性校验（不合法剔除并告警）→ lineNo 行分组。
     */
    private List<PageElement> mapRegionLines(OcrResult result, VisualRegion region, int[] crop,
                                             float dpi, float pageHeight, PageContext context,
                                             List<TextBlock> textBlocks) {
        Map<Integer, List<OcrWord>> lines = new TreeMap<>();
        int dedupCount = 0;
        int illegalCount = 0;
        for (OcrWord word : result.getPages().get(0).getWords()) {
            BoundingBox wordPdfBox = wordPdfBox(word, crop, dpi, pageHeight);
            if (coordinateMatcher.isDuplicateWord(wordPdfBox, textBlocks)) {
                dedupCount++;
                continue;
            }
            if (!coordinateMatcher.isWithinRegion(wordPdfBox, region.getBbox())) {
                illegalCount++;
                log.warn("OCR 元素未落在源区域内，已剔除 pageNumber={}, region={}, word={}",
                        context.getPageNumber(), region.getBbox(), wordPdfBox);
                continue;
            }
            lines.computeIfAbsent(word.getLineNo(), k -> new ArrayList<>()).add(word);
        }
        log.info("区域 OCR 完成 pageNumber={}, regionType={}, 去重={} 词, 非法剔除={} 词, 行数={}",
                context.getPageNumber(), region.getRegionType(), dedupCount, illegalCount, lines.size());
        return lines.values().stream()
                .map(line -> buildOcrLineElement(region, line, crop, dpi, pageHeight))
                .collect(Collectors.toList());
    }

    /**
     * OCR 词像素框（裁剪图坐标 + 裁剪偏移 = 整页像素坐标）→ PDF 用户空间框。
     */
    private BoundingBox wordPdfBox(OcrWord word, int[] crop, float dpi, float pageHeight) {
        BoundingBox fullPixelBox = BoundingBox.builder()
                .x(crop[0] + word.getX())
                .y(crop[1] + word.getY())
                .width(word.getWidth())
                .height(word.getHeight())
                .build();
        return coordinateMatcher.toPdfBox(fullPixelBox, dpi, pageHeight);
    }

    /**
     * 行 TEXT 元素：词按序空格连接、行 bbox = 词框并集、
     * confidence = 行内词置信度均值、regionType = 来源区域类型。
     */
    private PageElement buildOcrLineElement(VisualRegion region, List<OcrWord> lineWords,
                                            int[] crop, float dpi, float pageHeight) {
        BoundingBox bbox = null;
        float confidenceSum = 0f;
        for (OcrWord word : lineWords) {
            BoundingBox wordBox = wordPdfBox(word, crop, dpi, pageHeight);
            bbox = (bbox == null) ? wordBox : bbox.union(wordBox);
            confidenceSum += word.getConfidence();
        }
        String text = lineWords.stream()
                .map(OcrWord::getText)
                .collect(Collectors.joining(" "));
        return PageElement.builder()
                .type(PageElementType.TEXT)
                .source(ElementSource.OCR)
                .text(text)
                .bbox(bbox)
                .regionType(region.getRegionType())
                .confidence(confidenceSum / lineWords.size())
                .build();
    }

    // ---------- 区域元素与融合输出 ----------

    /**
     * 区域溯源元素：IMAGE_REGION + regionType，表格候选与决策输入经
     * description 承载（likelyTable 仅为候选标记，阶段 7 负责结构恢复）。
     */
    private PageElement buildRegionElement(VisualRegion region) {
        return PageElement.builder()
                .type(PageElementType.IMAGE_REGION)
                .source(ElementSource.IMAGE)
                .text("")
                .bbox(region.getBbox())
                .regionType(region.getRegionType())
                .description(String.format(
                        "likelyTable=%s tableScore=%.2f coverage=%.2f textCandidate=%.2f pageArea=%.2f; %s",
                        region.isLikelyTable(), region.getTableScore(), region.getCoverageRatio(),
                        region.getTextCandidateScore(), region.getPageAreaRatio(), region.getDescription()))
                .build();
    }

    /**
     * 元素融合排序：TEXT（PDF_TEXT + OCR）按阅读序（y 顶→底、x 左→右），
     * IMAGE_REGION 按检测序附后。
     */
    private List<PageElement> fuseElements(List<PageElement> textElements,
                                           List<PageElement> ocrElements,
                                           List<PageElement> regionElements) {
        List<PageElement> text = new ArrayList<>(textElements.size() + ocrElements.size());
        text.addAll(textElements);
        text.addAll(ocrElements);
        text.sort(Comparator
                .comparingDouble((PageElement e) -> e.getBbox().top()).reversed()
                .thenComparingDouble(e -> e.getBbox().getX()));
        List<PageElement> elements = new ArrayList<>(text.size() + regionElements.size());
        elements.addAll(text);
        elements.addAll(regionElements);
        return elements;
    }

    /**
     * 删除临时文件（渲染图/区域裁剪图即用即删，资源约束）。
     */
    private void deleteQuietly(File file) {
        if (file == null) {
            return;
        }
        try {
            Files.deleteIfExists(file.toPath());
        } catch (IOException e) {
            log.warn("临时文件删除失败: {}", file, e);
        }
    }

    /**
     * 组装统一输出模型（tables：无表格时为空列表，向后兼容）。
     */
    private PageDocument buildDocument(PageContext context, PageProfile profile,
                                       List<PageElement> elements, List<TableGrid> tables) {
        return PageDocument.builder()
                .pageNumber(context.getPageNumber())
                .contentType(profile.getContentType())
                .pageWidth(profile.getPageWidth())
                .pageHeight(profile.getPageHeight())
                .elements(elements)
                .tables(tables)
                .parserName(PARSER_NAME)
                .build();
    }
}
