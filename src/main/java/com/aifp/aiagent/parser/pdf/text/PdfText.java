package com.aifp.aiagent.parser.pdf.text;

import lombok.Builder;
import lombok.Data;

import java.util.List;
import java.util.stream.Collectors;

/**
 * 页级结构化文字：一页全部 {@link TextBlock} 的集合。
 * <p>
 * {@link #toPlainText()} 保持旧链路"全文 String"能力，
 * 供 ParserDocument.content 继续生成（阶段 3 验收要求）。
 *
 * @author Tang_tzb
 */
@Data
@Builder
public class PdfText {

    /**
     * 页码（1-based）
     */
    private int pageNumber;

    /**
     * 文字块列表（自上而下排列；空白页为空列表）
     */
    private List<TextBlock> blocks;

    /**
     * 页面纯文本：块文字按阅读顺序 \n 连接。
     */
    public String toPlainText() {
        if (blocks == null || blocks.isEmpty()) {
            return "";
        }
        return blocks.stream()
                .map(TextBlock::getText)
                .collect(Collectors.joining("\n"));
    }
}
