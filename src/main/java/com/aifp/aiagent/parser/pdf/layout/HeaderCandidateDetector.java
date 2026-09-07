package com.aifp.aiagent.parser.pdf.layout;

import com.aifp.aiagent.parser.pdf.region.CoordinateMatcher;
import com.aifp.aiagent.parser.pdf.region.RegionType;
import com.aifp.aiagent.parser.pdf.region.VisualRegion;
import com.aifp.aiagent.parser.pdf.text.BoundingBox;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;

/**
 * 表头候选判定器（阶段 7 v2，对应《PDF解析改造方案》§十"只对 Header Region OCR"）。
 * <p>
 * 表头候选<b>不得仅依据"无原生文字 + inkRatio"</b>，本判定器综合三类证据：
 * <ul>
 *   <li><b>硬前置</b>：该 cell 无任何值绑定（有值的数据 Cell 永不为表头候选）；</li>
 *   <li><b>Cell 位置先验</b>（满足其一）：①格网最左列（标签列形态）
 *       ②最顶行（表头行形态）③与某值单元格同行且紧邻其左侧；</li>
 *   <li><b>视觉文本特征</b>：墨迹占比 ∈ [label-ink-ratio, header-max-ink-ratio]——
 *       低于下限视为空白格，<b>高于上限视为图片/图形（普通 IMAGE 型数据 Cell），排除</b>；
 *       且裁剪区内存在类文字行带结构（行带高度 6~40pt），排除大面积实心图形/徽标；</li>
 *   <li><b>印章/装饰排除</b>：红色像素占比 ≥ 印章阈值，或与 STAMP/SIGNATURE
 *       区域有效交集 → 排除（印章不进 OCR）。</li>
 * </ul>
 * 候选产出带判定理由（位置/特征），供探针审计。
 *
 * @author Tang_tzb
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class HeaderCandidateDetector {

    /**
     * 文本带行投影阈值：行内暗像素占比 ≥ 该值视为含墨行。
     * <p>
     * 取 0.02（标签级小裁剪专调，与 DefaultRegionAnalyzer 大区域判据 0.08 脱钩）：
     * 汉字笔画稀疏，46pt 宽标签裁剪内文字行暗像素仅占行宽 3%~18% 且逐行波动大，
     * 0.08 会把连续文字带切碎为若干 &lt; 最小带高的碎片（真实样例 cell(1,3)
     * "建设单位"带高 20 行、行墨 31‰~179‰，0.08 判 bands=0 误拒）；0.02 保持
     * 带完整，而边框线（1~2px）、噪声（&lt; 2px/行）仍不满足最小带高 6pt 不成带。
     */
    private static final double TEXT_BAND_ROW_INK_RATIO = 0.02;
    /**
     * 文本带高度范围（pt）：手写/图形带通常超出该范围
     */
    private static final float TEXT_BAND_MIN_PT = 6f;
    private static final float TEXT_BAND_MAX_PT = 40f;
    /**
     * 印章/签名区域交集判定的外扩容差（pt，与 CoordinateMatcher 口径一致）
     */
    private static final float REGION_OVERLAP_TOLERANCE_PT = 2f;

    private final CoordinateMatcher coordinateMatcher;

    /**
     * 表头候选：墨迹占比下限（低于视为空白格）。
     * <p>
     * 取 0.005（仅作廉价初筛，语义过滤由文字带检查承担）：整体墨占比会被
     * 高单元格的垂直留白稀释——真实样例 cell(10,3) "监理单位"标签带 20 行
     * 位于 108px 高裁剪中部，整体 ink≈0.017，0.02 下限会误拒；真正空白格
     * （无带/无墨）由 textBands&lt;1 兜底排除。
     */
    @Value("${document.parser.pdf.table.label-ink-ratio:0.005}")
    private double labelInkRatio = 0.005;
    /**
     * 表头候选：墨迹占比上限（高于视为图片/图形，普通 IMAGE 型数据 Cell 排除）
     */
    @Value("${document.parser.pdf.table.header-max-ink-ratio:0.50}")
    private double headerMaxInkRatio = 0.50;
    /**
     * 印章排除：裁剪区红色像素占比阈值（复用 region 配置口径）
     */
    @Value("${document.parser.pdf.region.stamp-red-ratio:0.10}")
    private double stampRedRatio = 0.10;

    /**
     * 行区间重叠（[rowIndex, rowIndex+rowSpan) 相交）。
     */
    private static boolean rowsOverlap(TableCell a, TableCell b) {
        return a.getRowIndex() < b.getRowIndex() + b.getRowSpan()
                && b.getRowIndex() < a.getRowIndex() + a.getRowSpan();
    }

    // ---------- 位置先验 ----------

    /**
     * 行投影文本带计数：连续含墨行段高度落在文字带范围（6~40pt）。
     */
    private static int countTextBands(List<Integer> rowInkPerMille, int minBandRows, int maxBandRows) {
        int bands = 0;
        int run = 0;
        for (int perMille : rowInkPerMille) {
            if (perMille >= (int) (TEXT_BAND_ROW_INK_RATIO * 1000)) {
                run++;
            } else {
                if (run >= minBandRows && run <= maxBandRows) {
                    bands++;
                }
                run = 0;
            }
        }
        if (run >= minBandRows && run <= maxBandRows) {
            bands++;
        }
        return bands;
    }

    /**
     * 筛选表头候选单元格。
     *
     * @param cells      格网全部单元格（硬前置 = value 已绑定的单元格直接排除）
     * @param image      当页渲染图（候选裁剪像素分析）
     * @param dpi        渲染 DPI
     * @param pageHeight 页面高度（pt，坐标换算）
     * @param regions    视觉区域（STAMP/SIGNATURE 排除判定）
     * @return 候选列表（含判定理由）
     */
    public List<HeaderCandidate> detect(List<TableCell> cells, BufferedImage image,
                                        float dpi, float pageHeight, List<VisualRegion> regions) {
        List<HeaderCandidate> candidates = new ArrayList<>();
        if (cells == null || cells.isEmpty() || image == null) {
            return candidates;
        }
        int minColumn = cells.stream().mapToInt(TableCell::getColumnIndex).min().orElse(0);
        int minRow = cells.stream().mapToInt(TableCell::getRowIndex).min().orElse(0);
        for (TableCell cell : cells) {
            if (cell.getValue() != null) {
                // 硬前置：有值绑定的数据 Cell 永不为表头候选
                continue;
            }
            String position = positionPrior(cell, cells, minColumn, minRow);
            if (position == null) {
                continue;
            }
            PixelProfile profile = analyzeCrop(cell.getBoundingBox(), image, dpi, pageHeight);
            if (profile == null) {
                continue;
            }
            String rejectReason = rejectReason(profile, cell.getBoundingBox(), regions);
            if (rejectReason != null) {
                log.debug("表头候选排除 cell=({},{}) reason={}", cell.getRowIndex(),
                        cell.getColumnIndex(), rejectReason);
                continue;
            }
            candidates.add(new HeaderCandidate(cell, String.format("%s ink=%.3f bands=%d",
                    position, profile.inkRatio(), profile.textBands())));
        }
        log.info("表头候选筛选完成 候选={}/{}", candidates.size(), cells.size());
        return candidates;
    }

    // ---------- 视觉特征与排除 ----------

    /**
     * Cell 位置先验（满足其一）：最左列 / 最顶行 / 值单元格左邻；不满足返回 null。
     */
    private String positionPrior(TableCell cell, List<TableCell> cells, int minColumn, int minRow) {
        if (cell.getColumnIndex() == minColumn) {
            return "最左列";
        }
        if (cell.getRowIndex() == minRow) {
            return "最顶行";
        }
        for (TableCell bound : cells) {
            if (bound.getValue() != null && rowsOverlap(cell, bound)
                    && bound.getColumnIndex() == cell.getColumnIndex() + cell.getColSpan()) {
                return "值左邻";
            }
        }
        return null;
    }

    /**
     * 排除判定：命中排除项返回理由，通过返回 null。
     */
    private String rejectReason(PixelProfile profile, BoundingBox cellBbox,
                                List<VisualRegion> regions) {
        if (profile.redRatio() >= stampRedRatio) {
            return "红色像素=印章";
        }
        if (overlapsStampOrSignature(cellBbox, regions)) {
            return "与STAMP/SIGNATURE区域交集";
        }
        if (profile.inkRatio() < labelInkRatio) {
            return "空白格";
        }
        if (profile.inkRatio() > headerMaxInkRatio) {
            // 普通 IMAGE/图片型数据 Cell（大面积图形），不得作为表头 OCR
            return "墨迹超上限=图形/图片";
        }
        if (profile.textBands() < 1) {
            return "无类文字行带结构";
        }
        return null;
    }

    /**
     * 是否与 STAMP/SIGNATURE 视觉区域存在有效交集（外扩容差口径）。
     */
    private boolean overlapsStampOrSignature(BoundingBox cellBbox, List<VisualRegion> regions) {
        if (regions == null || regions.isEmpty() || cellBbox == null) {
            return false;
        }
        BoundingBox expanded = cellBbox.expand(REGION_OVERLAP_TOLERANCE_PT);
        for (VisualRegion region : regions) {
            RegionType type = region.getRegionType();
            if ((type == RegionType.STAMP || type == RegionType.SIGNATURE)
                    && expanded.intersection(region.getBbox()) != null) {
                return true;
            }
        }
        return false;
    }

    /**
     * 候选单元格裁剪像素分析：墨迹占比 / 红色占比 / 行投影文本带
     * （标签级小裁剪，步长 1 全采样；判据与 DefaultRegionAnalyzer 一致）。
     */
    private PixelProfile analyzeCrop(BoundingBox pdfBox, BufferedImage image,
                                     float dpi, float pageHeight) {
        if (pdfBox == null || pdfBox.getWidth() <= 0 || pdfBox.getHeight() <= 0) {
            return null;
        }
        int[] crop = coordinateMatcher.pixelCropBounds(pdfBox, dpi, pageHeight,
                image.getWidth(), image.getHeight());
        long sampled = 0;
        long ink = 0;
        long red = 0;
        List<Integer> rowInkPerMille = new ArrayList<>(crop[3]);
        for (int y = crop[1]; y < crop[1] + crop[3]; y++) {
            long rowDark = 0;
            for (int x = crop[0]; x < crop[0] + crop[2]; x++) {
                int rgb = image.getRGB(x, y);
                int r = (rgb >> 16) & 0xFF;
                int g = (rgb >> 8) & 0xFF;
                int b = rgb & 0xFF;
                sampled++;
                if (r < 100 && g < 100 && b < 100) {
                    ink++;
                    rowDark++;
                }
                if (r > 150 && r - g > 50 && r - b > 50) {
                    red++;
                }
            }
            rowInkPerMille.add((int) (rowDark * 1000 / Math.max(1, crop[2])));
        }
        if (sampled == 0) {
            return null;
        }
        float k = dpi / 72f;
        int minBandRows = Math.max(1, Math.round(TEXT_BAND_MIN_PT * k));
        int maxBandRows = Math.max(minBandRows, Math.round(TEXT_BAND_MAX_PT * k));
        return new PixelProfile(ink / (double) sampled, red / (double) sampled,
                countTextBands(rowInkPerMille, minBandRows, maxBandRows));
    }

    /**
     * 表头候选：单元格 + 判定理由（探针可审计）。
     */
    public record HeaderCandidate(TableCell cell, String reason) {
    }

    /**
     * 裁剪像素画像。
     */
    private record PixelProfile(double inkRatio, double redRatio, int textBands) {
    }
}
