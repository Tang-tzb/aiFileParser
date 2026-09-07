package com.aifp.aiagent.parser.pdf.page;

import com.aifp.aiagent.common.ResultCode;
import com.aifp.aiagent.exception.BusinessException;
import com.aifp.aiagent.parser.ocr.*;
import com.aifp.aiagent.parser.pdf.PageContentType;
import com.aifp.aiagent.parser.pdf.PageImageRenderer;
import com.aifp.aiagent.parser.pdf.PageProfile;
import com.aifp.aiagent.parser.pdf.text.BoundingBox;
import com.aifp.aiagent.parser.pdf.text.CoordinateTransformer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 整页图片页解析器（IMAGE_ONLY）：PDF → 图片渲染 → OCR → OcrResult → PageDocument。
 * <p>
 * 阶段 4 流程：{@link PageImageRenderer} 单页渲染临时 PNG（finally 删除）→
 * {@link OcrParser#recognize(OcrRequest)} 状态化识别 → 按 OcrStatus 三态分支：
 * FAILED 转 {@link ResultCode#FILE_OCR_ERROR} 明确报错、EMPTY 输出空元素（空白扫描页
 * 为正常业务态）、SUCCESS 按 lineNo 行分组映射 TEXT 元素。
 * <p>
 * 坐标换算不在本类内嵌公式：词级像素框经 {@link CoordinateTransformer}
 * 转为 PDF 用户空间（与 TextPageParser 输出同系）后取行内并集；
 * lineNo 仅为 OCR 引擎原始布局元数据，不作最终 Layout 行模型。
 *
 * @author Tang_tzb
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ImagePageParser implements PageParser {

    /**
     * 解析器名（写入 PageDocument.parserName，供路由验收与溯源）
     */
    static final String PARSER_NAME = "ImagePageParser";

    private final PageImageRenderer pageImageRenderer;
    private final OcrParser ocrParser;
    private final CoordinateTransformer coordinateTransformer;

    @Override
    public PageContentType supportedType() {
        return PageContentType.IMAGE_ONLY;
    }

    @Override
    public PageDocument parse(PageContext context) {
        PageProfile profile = context.getProfile();
        List<PageElement> elements = new ArrayList<>();

        PageImageRenderer.RenderedPage rendered = null;
        try {
            rendered = pageImageRenderer.renderToTempPng(context.getDocument(), context.getPageIndex());
            OcrResult result = recognize(rendered, context);
            elements = mapElements(result, context, rendered);
        } catch (java.io.IOException e) {
            log.error("页面渲染失败 pageIndex={}", context.getPageIndex(), e);
            throw new BusinessException(ResultCode.FILE_PARSE_ERROR,
                    "页面渲染失败: 第" + context.getPageNumber() + "页");
        } finally {
            deleteQuietly(rendered);
        }

        return buildDocument(context, profile, elements);
    }

    /**
     * 调用 OCR 引擎（状态化结果，引擎层不抛异常）。
     */
    private OcrResult recognize(PageImageRenderer.RenderedPage rendered, PageContext context) {
        OcrRequest request = OcrRequest.builder()
                .imageFile(rendered.getFile())
                .pageNumber(context.getPageNumber())
                .imageWidth(rendered.getWidth())
                .imageHeight(rendered.getHeight())
                .dpi(rendered.getDpi())
                .build();
        return ocrParser.recognize(request);
    }

    /**
     * 按 OcrStatus 三态映射页面元素：
     * FAILED → 明确报错；EMPTY → 空元素；SUCCESS → 逐行映射 TEXT 元素。
     */
    private List<PageElement> mapElements(OcrResult result, PageContext context,
                                          PageImageRenderer.RenderedPage rendered) {
        if (result.getStatus() == OcrStatus.FAILED) {
            // OCR 失败 → 明确错误（errorMessage 来自引擎层）
            throw new BusinessException(ResultCode.FILE_OCR_ERROR, result.getErrorMessage());
        }
        if (result.getStatus() == OcrStatus.EMPTY) {
            log.info("OCR 未识别到文字（空白扫描页） pageNumber={}", context.getPageNumber());
            return List.of();
        }
        return mapSuccessLines(result, context, rendered);
    }

    /**
     * SUCCESS 映射：lineNo 连续行段 → TEXT 元素（词空格连接、坐标换算后 union、
     * confidence = 行内词置信度均值，对齐 PageElement 契约与 MixedPageParser 口径）。
     */
    private List<PageElement> mapSuccessLines(OcrResult result, PageContext context,
                                              PageImageRenderer.RenderedPage rendered) {
        List<List<OcrWord>> lines = result.linesOf(context.getPageNumber());
        List<PageElement> elements = new ArrayList<>(lines.size());
        for (List<OcrWord> line : lines) {
            elements.add(PageElement.builder()
                    .type(PageElementType.TEXT)
                    .source(ElementSource.OCR)
                    .text(lineText(line))
                    .bbox(lineBbox(line, rendered, context))
                    .fontSize(null)
                    .fontName(null)
                    .confidence(lineConfidence(line))
                    .build());
        }
        return elements;
    }

    /**
     * 行置信度：行内词置信度均值（0~100 口径，AST 层归一）。
     */
    private float lineConfidence(List<OcrWord> line) {
        float sum = 0f;
        for (OcrWord word : line) {
            sum += word.getConfidence();
        }
        return line.isEmpty() ? 0f : sum / line.size();
    }

    /**
     * 行文字：词按序空格连接。
     */
    private String lineText(List<OcrWord> line) {
        return line.stream()
                .map(OcrWord::getText)
                .reduce((a, b) -> a + " " + b)
                .orElse("");
    }

    /**
     * 行外接矩形：词级像素框经坐标换算（像素空间 → PDF 用户空间）后取并集。
     */
    private BoundingBox lineBbox(List<OcrWord> line, PageImageRenderer.RenderedPage rendered,
                                 PageContext context) {
        float pageHeight = context.getProfile().getPageHeight();
        BoundingBox bbox = null;
        for (OcrWord word : line) {
            BoundingBox pixelBox = BoundingBox.builder()
                    .x(word.getX())
                    .y(word.getY())
                    .width(word.getWidth())
                    .height(word.getHeight())
                    .build();
            BoundingBox pdfBox = coordinateTransformer.imageToPdf(pixelBox, rendered.getDpi(), pageHeight);
            bbox = (bbox == null) ? pdfBox : bbox.union(pdfBox);
        }
        return bbox;
    }

    /**
     * 删除渲染临时文件（资源约束：即用即删）。
     */
    private void deleteQuietly(PageImageRenderer.RenderedPage rendered) {
        if (rendered != null && rendered.getFile() != null) {
            try {
                java.nio.file.Files.deleteIfExists(rendered.getFile().toPath());
            } catch (java.io.IOException e) {
                log.warn("渲染临时文件删除失败: {}", rendered.getFile(), e);
            }
        }
    }

    /**
     * 组装统一输出模型。
     */
    private PageDocument buildDocument(PageContext context, PageProfile profile, List<PageElement> elements) {
        return PageDocument.builder()
                .pageNumber(context.getPageNumber())
                .contentType(profile.getContentType())
                .pageWidth(profile.getPageWidth())
                .pageHeight(profile.getPageHeight())
                .elements(elements)
                .parserName(PARSER_NAME)
                .build();
    }
}
