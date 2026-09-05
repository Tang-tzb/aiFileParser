package com.aifp.aiagent.parser.pdf.page;

import com.aifp.aiagent.parser.pdf.PageContentType;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 空白页解析器（EMPTY）。
 * <p>
 * 输出无元素的 {@link PageDocument}，保留页码/尺寸与类型标识，
 * 供上层编排层（阶段 10 文档组装）按页序聚合。
 *
 * @author Tang_tzb
 */
@Component
public class EmptyPageParser implements PageParser {

    /**
     * 解析器名（写入 PageDocument.parserName，供路由验收与溯源）
     */
    static final String PARSER_NAME = "EmptyPageParser";

    @Override
    public PageContentType supportedType() {
        return PageContentType.EMPTY;
    }

    @Override
    public PageDocument parse(PageContext context) {
        return PageDocument.builder()
                .pageNumber(context.getPageNumber())
                .contentType(context.getProfile().getContentType())
                .pageWidth(context.getProfile().getPageWidth())
                .pageHeight(context.getProfile().getPageHeight())
                .elements(List.of())
                .parserName(PARSER_NAME)
                .build();
    }
}
