package com.aifp.aiagent.parser.pdf.page;

import com.aifp.aiagent.parser.pdf.PageContentType;

/**
 * 页面解析策略接口：把"PDF 内容解析"与"PDF 类型判断"分离（阶段 2 目标）。
 * <p>
 * 每个实现负责一种 {@link com.aifp.aiagent.parser.pdf.PageContentType}，
 * 由 {@link PageParserRouter} 依据页面画像完成注册表式路由；
 * 统一返回 {@link PageDocument}，不允许返回 String。
 *
 * @author Tang_tzb
 */
public interface PageParser {

    /**
     * 本解析器支持的页面内容类型（注册表 key）。
     */
    PageContentType supportedType();

    /**
     * 解析一页。
     *
     * @param context 页面解析上下文（文档 + 页索引 + 页面画像）
     * @return 页面文档（统一输出模型）
     */
    PageDocument parse(PageContext context);
}
