package com.aifp.aiagent.parser.pdf.page;

import com.aifp.aiagent.parser.pdf.PageContentType;
import com.aifp.aiagent.parser.pdf.PageProfile;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 图文混合页解析器（MIXED）——本阶段为处理骨架。
 * <p>
 * 文字层复用 {@link TextPageParser}（单一来源，避免重复实现）；
 * 图片区域仅输出 PENDING_OCR 占位（携带检测层概要信息），
 * 真实的视觉区域识别（阶段 5）、表格结构（阶段 6/7）与多源坐标融合（阶段 8）
 * 在后续阶段填充，输出模型保持稳定。
 *
 * @author Tang_tzb
 */
@Component
@RequiredArgsConstructor
public class MixedPageParser implements PageParser {

    /**
     * 解析器名（写入 PageDocument.parserName，供路由验收与溯源）
     */
    static final String PARSER_NAME = "MixedPageParser";

    private final TextPageParser textPageParser;

    @Override
    public PageContentType supportedType() {
        return PageContentType.MIXED;
    }

    @Override
    public PageDocument parse(PageContext context) {
        PageProfile profile = context.getProfile();

        // 骨架：文字层直接复用纯文字页解析器结果
        PageDocument textPart = textPageParser.parse(context);
        List<PageElement> elements = new ArrayList<>(textPart.getElements());
        elements.add(buildPendingElement(profile));

        return PageDocument.builder()
                .pageNumber(textPart.getPageNumber())
                .contentType(profile.getContentType())
                .pageWidth(profile.getPageWidth())
                .pageHeight(profile.getPageHeight())
                .elements(elements)
                .parserName(PARSER_NAME)
                .build();
    }

    /**
     * 图片区域待融合占位：携带检测层统计概要，供后续阶段按页取数。
     */
    private PageElement buildPendingElement(PageProfile profile) {
        return PageElement.builder()
                .type(PageElementType.PENDING_OCR)
                .source(ElementSource.IMAGE)
                .text("")
                .description(String.format("检测到 %d 处图片区域（整页图=%s），待阶段 5/6 区域结构化与阶段 8 坐标融合",
                        profile.getImageCount(), profile.isHasFullPageImage()))
                .build();
    }
}
