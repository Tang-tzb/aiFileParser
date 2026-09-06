package com.aifp.aiagent.parser.pdf.region;

import com.aifp.aiagent.parser.pdf.text.TextBlock;
import org.apache.pdfbox.pdmodel.PDDocument;

import java.awt.image.BufferedImage;
import java.util.List;

/**
 * 视觉区域分析器：MIXED 页"图片区域提取 + 分类 + 表格候选标记 + 覆盖率基础数据"。
 * <p>
 * 职责边界（阶段 6 冻结）：仅占位提取/分类/候选标记/基础数据计算；
 * 不做 OpenCV 表格线检测、TableGrid、TableCell、rowSpan、colSpan、表头融合（阶段 7/8）。
 * 像素采样使用调用方传入的当页唯一渲染图（全页只渲染一次）。
 *
 * @author Tang_tzb
 */
public interface RegionAnalyzer {

    /**
     * 分析页面视觉区域。
     *
     * @param document      已加载 PDF 文档
     * @param pageIndex     页索引（0-based）
     * @param textBlocks    该页 PDF 原生文字块（覆盖源与表格候选数据）
     * @param renderedImage 当页渲染图（可为 null，此时分类降级 UNKNOWN）
     * @param dpi           渲染图 DPI
     * @return 视觉区域列表（PDF 用户空间坐标）
     */
    List<VisualRegion> analyze(PDDocument document, int pageIndex, List<TextBlock> textBlocks,
                               BufferedImage renderedImage, float dpi);
}
