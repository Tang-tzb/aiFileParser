package com.aifp.aiagent.parser.pdf.page;

import com.aifp.aiagent.parser.pdf.PageContentType;
import com.aifp.aiagent.parser.pdf.PageProfile;
import com.aifp.aiagent.parser.pdf.text.PdfText;
import com.aifp.aiagent.parser.pdf.text.PdfTextExtractor;
import com.aifp.aiagent.parser.pdf.text.TextBlock;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 纯文字页解析器（TEXT_ONLY）。
 * <p>
 * 阶段 3 起使用结构化文字提取器 {@link PdfTextExtractor}：
 * 每个 {@link TextBlock} 输出一个带坐标（bbox）与字体信息的 TEXT 元素，
 * 取代阶段 2 的整页拍平；混合页经 {@link MixedPageParser} 组合复用自动受益。
 *
 * @author Tang_tzb
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TextPageParser implements PageParser {

    /**
     * 解析器名（写入 PageDocument.parserName，供路由验收与溯源）
     */
    static final String PARSER_NAME = "TextPageParser";

    private final PdfTextExtractor textExtractor;

    @Override
    public PageContentType supportedType() {
        return PageContentType.TEXT_ONLY;
    }

    @Override
    public PageDocument parse(PageContext context) {
        PageProfile profile = context.getProfile();
        PdfText text = textExtractor.extract(context.getDocument(), context.getPageIndex());

        List<PageElement> elements = new ArrayList<>();
        for (TextBlock block : text.getBlocks()) {
            elements.add(PageElement.builder()
                    .type(PageElementType.TEXT)
                    .source(ElementSource.PDF_TEXT)
                    .text(block.getText())
                    .bbox(block.getBbox())
                    .fontSize(block.getFontSize())
                    .fontName(block.getFontName())
                    .build());
        }
        return buildDocument(context, profile, elements);
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
