package com.aifp.aiagent.parser.pdf.page;

import com.aifp.aiagent.parser.pdf.PageContentType;
import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * 页面解析统一输出（对应《PDF解析改造方案》第五节 PageDocument 契约）。
 * <p>
 * 解析器不允许直接返回 String，统一返回本模型；后续阶段通过扩展
 * {@link PageElement} 体系逐步升级为结构化页面文档，接口保持稳定（§42）。
 *
 * @author Tang_tzb
 */
@Data
@Builder
public class PageDocument {

    /**
     * 页码（1-based）
     */
    private int pageNumber;

    /**
     * 页面内容类型（与 PageProfile 判定一致）
     */
    private PageContentType contentType;

    /**
     * 页面宽度（MediaBox，单位 pt）
     */
    private float pageWidth;

    /**
     * 页面高度（MediaBox，单位 pt）
     */
    private float pageHeight;

    /**
     * 页内元素列表（EMPTY 页为空列表）
     */
    private List<PageElement> elements;

    /**
     * 产出本页文档的解析器名（路由验收证据）
     */
    private String parserName;
}
