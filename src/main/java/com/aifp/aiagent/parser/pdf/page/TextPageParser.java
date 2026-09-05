package com.aifp.aiagent.parser.pdf.page;

import com.aifp.aiagent.common.ResultCode;
import com.aifp.aiagent.exception.BusinessException;
import com.aifp.aiagent.parser.pdf.PageContentType;
import com.aifp.aiagent.parser.pdf.PageProfile;
import lombok.extern.slf4j.Slf4j;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * 纯文字页解析器（TEXT_ONLY）。
 * <p>
 * 本阶段先用 PDFBox 整页抽取文字（无坐标），结构化 TextBlock/坐标
 * 由阶段 3 {@code PdfTextExtractor} 升级；整页文字输出为单个 TEXT 元素。
 *
 * @author Tang_tzb
 */
@Slf4j
@Component
public class TextPageParser implements PageParser {

    /**
     * 解析器名（写入 PageDocument.parserName，供路由验收与溯源）
     */
    static final String PARSER_NAME = "TextPageParser";

    @Override
    public PageContentType supportedType() {
        return PageContentType.TEXT_ONLY;
    }

    @Override
    public PageDocument parse(PageContext context) {
        PageProfile profile = context.getProfile();
        String text = extractPageText(context.getDocument(), context.getPageIndex());

        List<PageElement> elements = new ArrayList<>();
        if (!text.isBlank()) {
            elements.add(PageElement.builder()
                    .type(PageElementType.TEXT)
                    .source(ElementSource.PDF_TEXT)
                    .text(text)
                    .build());
        }
        return buildDocument(context, profile, elements);
    }

    /**
     * 按起止页抽取单页文字并去除首尾空白；IO 异常按模块约定包装。
     */
    private String extractPageText(PDDocument document, int pageIndex) {
        try {
            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setStartPage(pageIndex + 1);
            stripper.setEndPage(pageIndex + 1);
            return stripper.getText(document).strip();
        } catch (IOException e) {
            throw new BusinessException(ResultCode.FILE_PARSE_ERROR,
                    "PDF 页面文字抽取失败: 第" + (pageIndex + 1) + "页");
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
