package com.aifp.aiagent.parser.pdf.ast;

import com.aifp.aiagent.parser.pdf.PageContentType;
import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * 页节点（阶段 9）：单页 AST 内容的容器。
 * <p>
 * 双列表约定（用户约束 1/2，2026-09-07）：
 * <ul>
 *   <li>{@link #nodes}：<b>确定性阅读顺序</b>的线性内容节点（标题/段落/表格/占位），
 *       按 bbox 排序——顶部优先（top=y+height 降序）、同顶按 x 升序、无 bbox 殿后，
 *       稳定排序保证跨运行结果确定；</li>
 *   <li>{@link #keyValues}：键值对<b>语义视图</b>，不参与阅读序、不参与 Markdown
 *       渲染（阶段 11 只遍历 nodes，表格已含表头列与值列，杜绝重复输出），
 *       仅供阶段 12 Chunker 与字段抽取消费。</li>
 * </ul>
 *
 * @author Tang_tzb
 */
@Data
@Builder
public class PageNode {

    /**
     * 页码（1-based）
     */
    private int pageNumber;

    /**
     * 页面内容类型（与 PageDocument 一致）
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
     * 线性内容节点（确定性阅读顺序，见类注释约束 1）
     */
    private List<DocumentNode> nodes;

    /**
     * 键值语义视图（不进入 nodes，见类注释约束 2；无键值页为空列表）
     */
    @Builder.Default
    private List<KeyValueNode> keyValues = List.of();
}
