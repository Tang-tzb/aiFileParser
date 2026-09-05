package com.aifp.aiagent.parser.pdf.page;

import com.aifp.aiagent.parser.pdf.PageProfile;
import lombok.Builder;
import lombok.Data;
import org.apache.pdfbox.pdmodel.PDDocument;

/**
 * 页面解析上下文：一次页面解析的全部输入。
 * <p>
 * 将"类型判断"产物（{@link PageProfile}）与待解析文档一起传递给
 * {@link PageParser}，使解析器无需重复检测页面类型。
 *
 * @author Tang_tzb
 */
@Data
@Builder
public class PageContext {

    /**
     * 已加载的 PDF 文档（调用方负责关闭）
     */
    private PDDocument document;

    /**
     * 页索引（0-based）
     */
    private int pageIndex;

    /**
     * 页面画像（来自阶段 1 检测层，含内容类型判定）
     */
    private PageProfile profile;

    /**
     * 页码（1-based，对齐 PDF 惯例）
     */
    public int getPageNumber() {
        return pageIndex + 1;
    }
}
