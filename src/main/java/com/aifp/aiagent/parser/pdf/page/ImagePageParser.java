package com.aifp.aiagent.parser.pdf.page;

import com.aifp.aiagent.parser.pdf.PageContentType;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 整页图片页解析器（IMAGE_ONLY）。
 * <p>
 * 本阶段不渲染、不做 OCR：输出一个明确的 PENDING_OCR 占位元素，
 * 标记整页内容待处理；真实 OCR（OCRmyPDF/Tesseract，带坐标与置信度）
 * 由阶段 4 接入后填充，占位元素类型保持稳定。
 *
 * @author Tang_tzb
 */
@Component
public class ImagePageParser implements PageParser {

    /**
     * 解析器名（写入 PageDocument.parserName，供路由验收与溯源）
     */
    static final String PARSER_NAME = "ImagePageParser";

    @Override
    public PageContentType supportedType() {
        return PageContentType.IMAGE_ONLY;
    }

    @Override
    public PageDocument parse(PageContext context) {
        PageElement pending = PageElement.builder()
                .type(PageElementType.PENDING_OCR)
                .source(ElementSource.IMAGE)
                .text("")
                .description("整页图片待 OCR（阶段 4 接入 OCRmyPDF/Tesseract 后填充）")
                .build();

        return PageDocument.builder()
                .pageNumber(context.getPageNumber())
                .contentType(context.getProfile().getContentType())
                .pageWidth(context.getProfile().getPageWidth())
                .pageHeight(context.getProfile().getPageHeight())
                .elements(List.of(pending))
                .parserName(PARSER_NAME)
                .build();
    }
}
