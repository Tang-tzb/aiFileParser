package com.aifp.aiagent.parser.pdf.region;

import com.aifp.aiagent.parser.pdf.text.BoundingBox;
import com.aifp.aiagent.parser.pdf.text.TextBlock;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.pdfbox.contentstream.PDFStreamEngine;
import org.apache.pdfbox.contentstream.operator.DrawObject;
import org.apache.pdfbox.contentstream.operator.Operator;
import org.apache.pdfbox.contentstream.operator.state.*;
import org.apache.pdfbox.cos.COSBase;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.graphics.PDXObject;
import org.apache.pdfbox.pdmodel.graphics.form.PDFormXObject;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 默认视觉区域分析器。
 * <p>
 * 职责（阶段 6 边界）：① PDF 图片对象占位提取（CTM，用户空间）② 区域分类
 * （红章/签名/图片启发式，基于当页渲染图裁剪采样）③ 表格候选标记（原生文字
 * 网格对齐，<b>仅候选，非表格识别</b>）④ coverage 与视觉文本候选基础数据。
 * 不做 OpenCV 表格线检测 / TableGrid / TableCell / 表头融合（阶段 7/8）。
 * 像素采样步长自适应，控制 2C8G 机器开销。
 *
 * @author Tang_tzb
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DefaultRegionAnalyzer implements RegionAnalyzer {

    /**
     * SIGNATURE 判定：墨迹占比上限（细笔画特征）
     */
    private static final double SIGNATURE_MAX_INK_RATIO = 0.15;
    /**
     * 文本带行投影阈值：行内暗像素占比 ≥ 该值视为含墨行
     */
    private static final double TEXT_BAND_ROW_INK_RATIO = 0.08;
    /**
     * 文本带高度范围（pt，换算像素后判定）：手写/图形带通常超出该范围
     */
    private static final float TEXT_BAND_MIN_PT = 6f;
    private static final float TEXT_BAND_MAX_PT = 40f;
    /**
     * 表格候选：x 起点对齐容差（pt）
     */
    private static final float TABLE_ALIGN_TOLERANCE_PT = 2f;
    /**
     * 覆盖率计算复用（与去重同一交集并集口径，避免口径分叉）
     */
    private final CoordinateMatcher coordinateMatcher;
    /**
     * STAMP 判定：裁剪区域红色像素占比阈值
     */
    @Value("${document.parser.pdf.region.stamp-red-ratio:0.10}")
    private double stampRedRatio = 0.10;

    private static boolean isDark(int rgb) {
        int r = (rgb >> 16) & 0xFF;
        int g = (rgb >> 8) & 0xFF;
        int b = rgb & 0xFF;
        return r < 100 && g < 100 && b < 100;
    }

    // ---------- 图片占位提取 ----------

    /**
     * 区域像素裁剪范围（钳制到渲染图边界；无效时返回 null）。
     */
    private static int[] cropBounds(BoundingBox placement, BufferedImage image,
                                    float dpi, float pageHeight) {
        float k = dpi / 72f;
        int x = Math.max(0, (int) Math.floor(placement.getX() * k));
        int y = Math.max(0, (int) Math.floor((pageHeight - placement.top()) * k));
        int x1 = Math.min(image.getWidth(), (int) Math.ceil(placement.right() * k));
        int y1 = Math.min(image.getHeight(), (int) Math.ceil((pageHeight - placement.getY()) * k));
        if (x1 - x < 1 || y1 - y < 1) {
            return new int[]{0, 0, 1, 1};
        }
        return new int[]{x, y, x1 - x, y1 - y};
    }

    @Override
    public List<VisualRegion> analyze(PDDocument document, int pageIndex, List<TextBlock> textBlocks,
                                      BufferedImage renderedImage, float dpi) {
        PDPage page = document.getPage(pageIndex);
        PDRectangle mediaBox = page.getMediaBox();
        float pageWidth = mediaBox.getWidth();
        float pageHeight = mediaBox.getHeight();
        double pageArea = (double) pageWidth * pageHeight;

        List<VisualRegion> regions = new ArrayList<>();
        for (BoundingBox placement : extractImagePlacements(page)) {
            regions.add(buildImageRegion(placement, renderedImage, dpi, pageHeight,
                    pageArea, textBlocks));
        }
        VisualRegion tableCandidate = detectTableCandidate(textBlocks, pageArea, pageHeight);
        if (tableCandidate != null) {
            regions.add(tableCandidate);
        }
        log.info("区域分析完成 pageIndex={}, 图片区域={} 个, 表格候选={}",
                pageIndex + 1, regions.size() - (tableCandidate != null ? 1 : 0),
                tableCandidate != null);
        return regions;
    }

    // ---------- 单区域构建与分类 ----------

    /**
     * 提取图片占位矩形（用户空间），同位重复绘制（iou>0.98）合并。
     */
    private List<BoundingBox> extractImagePlacements(PDPage page) {
        ImagePlacementEngine engine = new ImagePlacementEngine();
        try {
            engine.processPage(page);
        } catch (Exception e) {
            log.warn("图片占位提取降级（已提取 {} 个）: {}", engine.placements.size(), e.getMessage());
        }
        List<BoundingBox> placements = new ArrayList<>();
        for (BoundingBox box : engine.placements) {
            boolean duplicated = placements.stream().anyMatch(p -> p.iou(box) > 0.98);
            if (!duplicated) {
                placements.add(box);
            }
        }
        return placements;
    }

    private VisualRegion buildImageRegion(BoundingBox placement, BufferedImage renderedImage,
                                          float dpi, float pageHeight, double pageArea,
                                          List<TextBlock> textBlocks) {
        PixelStats stats = analyzePixels(placement, renderedImage, dpi, pageHeight);
        RegionType type = classify(stats, placement, renderedImage, pageHeight);
        double pageAreaRatio = pageArea > 0
                ? (double) placement.getWidth() * placement.getHeight() / pageArea : 0;
        return VisualRegion.builder()
                .regionType(type)
                .bbox(placement)
                .likelyTable(false)
                .tableScore(0)
                .coverageRatio(coordinateMatcher.coverageRatio(placement, textBlocks))
                .textCandidateScore(stats.textCandidateScore)
                .pageAreaRatio(pageAreaRatio)
                .description(String.format("red=%.2f ink=%.2f bands=%d", stats.redRatio,
                        stats.inkRatio, stats.textBands))
                .build();
    }

    // ---------- 像素采样与文本候选 ----------

    private RegionType classify(PixelStats stats, BoundingBox placement,
                                BufferedImage renderedImage, float pageHeight) {
        if (renderedImage == null) {
            return RegionType.UNKNOWN;
        }
        if (stats.redRatio >= stampRedRatio) {
            return RegionType.STAMP;
        }
        // 签名启发式：低墨迹细笔画 + 区域中心位于页面下半部（手写习惯位置）
        boolean lowerHalf = (placement.getY() + placement.getHeight() / 2f) < pageHeight / 2f;
        if (stats.inkRatio > 0 && stats.inkRatio <= SIGNATURE_MAX_INK_RATIO && lowerHalf) {
            return RegionType.SIGNATURE;
        }
        return RegionType.IMAGE;
    }

    /**
     * 区域裁剪像素采样：红色占比 / 墨迹占比 / 行投影文本带（步长自适应）。
     */
    private PixelStats analyzePixels(BoundingBox placement, BufferedImage image,
                                     float dpi, float pageHeight) {
        PixelStats stats = new PixelStats();
        if (image == null) {
            return stats;
        }
        int[] crop = cropBounds(placement, image, dpi, pageHeight);
        int stride = Math.max(1, (int) Math.sqrt((double) crop[2] * crop[3] / 250_000));

        long sampled = 0;
        long red = 0;
        long ink = 0;
        int rows = 0;
        int inkRows = 0;
        int textBands = 0;
        int bandHeightRows = 0;
        float bandMinRows = TEXT_BAND_MIN_PT * dpi / 72f;
        float bandMaxRows = TEXT_BAND_MAX_PT * dpi / 72f;

        for (int y = crop[1]; y < crop[1] + crop[3]; y += stride) {
            long rowDark = 0;
            long rowSampled = 0;
            for (int x = crop[0]; x < crop[0] + crop[2]; x += stride) {
                int rgb = image.getRGB(x, y);
                int r = (rgb >> 16) & 0xFF;
                int g = (rgb >> 8) & 0xFF;
                int b = rgb & 0xFF;
                sampled++;
                if (r > 150 && r - g > 50 && r - b > 50) {
                    red++;
                }
                boolean dark = r < 100 && g < 100 && b < 100;
                if (dark) {
                    ink++;
                    rowDark++;
                }
                rowSampled++;
            }
            rows++;
            if (rowSampled > 0 && (double) rowDark / rowSampled >= TEXT_BAND_ROW_INK_RATIO) {
                inkRows++;
            }
        }
        // 行投影文本带：连续含墨行段高度落在文字带范围
        boolean inBand = false;
        for (int y = crop[1]; y < crop[1] + crop[3]; y += stride) {
            long rowDark = 0;
            long rowSampled = 0;
            for (int x = crop[0]; x < crop[0] + crop[2]; x += stride) {
                int rgb = image.getRGB(x, y);
                rowSampled++;
                if (isDark(rgb)) {
                    rowDark++;
                }
            }
            boolean inkRow = rowSampled > 0
                    && (double) rowDark / rowSampled >= TEXT_BAND_ROW_INK_RATIO;
            if (inkRow) {
                inBand = true;
                bandHeightRows++;
            } else if (inBand) {
                if (bandHeightRows >= bandMinRows && bandHeightRows <= bandMaxRows) {
                    textBands++;
                }
                inBand = false;
                bandHeightRows = 0;
            }
        }
        if (inBand && bandHeightRows >= bandMinRows && bandHeightRows <= bandMaxRows) {
            textBands++;
        }

        stats.redRatio = sampled == 0 ? 0 : (double) red / sampled;
        stats.inkRatio = sampled == 0 ? 0 : (double) ink / sampled;
        stats.textBands = textBands;
        stats.textCandidateScore = Math.min(1d, textBands / 3d);
        log.debug("区域像素采样 crop={} stride={} sampled={} red={} ink={} bands={}",
                crop[0] + "," + crop[1] + "," + crop[2] + "," + crop[3],
                stride, sampled, stats.redRatio, stats.inkRatio, textBands);
        return stats;
    }

    /**
     * 原生文字网格表格候选：textBlocks 按 x 起点（±容差）分列，
     * ≥2 列且每列 ≥2 块对齐 → TABLE 候选区域（bbox=相关块并集）。
     * <b>仅候选标记，不代表表格识别完成</b>（阶段 7 负责结构恢复）。
     */
    private VisualRegion detectTableCandidate(List<TextBlock> textBlocks,
                                              double pageArea, float pageHeight) {
        if (textBlocks == null || textBlocks.size() < 4) {
            return null;
        }
        // 按 x 起点聚列（容差对齐）
        List<List<TextBlock>> columns = new ArrayList<>();
        List<TextBlock> sorted = new ArrayList<>(textBlocks);
        sorted.sort(Comparator.comparingDouble(b -> b.getBbox().getX()));
        for (TextBlock block : sorted) {
            boolean matched = false;
            for (List<TextBlock> column : columns) {
                float colX = column.get(0).getBbox().getX();
                if (Math.abs(block.getBbox().getX() - colX) <= TABLE_ALIGN_TOLERANCE_PT) {
                    column.add(block);
                    matched = true;
                    break;
                }
            }
            if (!matched) {
                List<TextBlock> column = new ArrayList<>();
                column.add(block);
                columns.add(column);
            }
        }
        List<List<TextBlock>> alignedColumns = columns.stream()
                .filter(c -> c.size() >= 2)
                .toList();
        if (alignedColumns.size() < 2) {
            return null;
        }
        BoundingBox union = null;
        int blocksInGrid = 0;
        for (List<TextBlock> column : alignedColumns) {
            for (TextBlock block : column) {
                union = (union == null) ? block.getBbox() : union.union(block.getBbox());
                blocksInGrid++;
            }
        }
        // 参与网格的块需占全部文字块一半以上，避免普通段落误判
        if (blocksInGrid < textBlocks.size() / 2) {
            return null;
        }
        return VisualRegion.builder()
                .regionType(RegionType.TABLE)
                .bbox(union)
                .likelyTable(true)
                .tableScore(Math.min(1d, alignedColumns.size() / 4d))
                .coverageRatio(1d)
                .textCandidateScore(0)
                .pageAreaRatio(pageArea > 0
                        ? (double) union.getWidth() * union.getHeight() / pageArea : 0)
                .description(String.format("原生文字网格候选: 列=%d 块=%d（仅候选，阶段7恢复结构）",
                        alignedColumns.size(), blocksInGrid))
                .build();
    }

    /**
     * 图片占位引擎：与检测层 ImageAreaEngine 同构，但记录 bbox 而非仅统计。
     */
    private static class ImagePlacementEngine extends PDFStreamEngine {
        private final List<BoundingBox> placements = new ArrayList<>();

        ImagePlacementEngine() {
            addOperator(new Concatenate(this));
            addOperator(new DrawObject(this));
            addOperator(new SetGraphicsStateParameters(this));
            addOperator(new Save(this));
            addOperator(new Restore(this));
            addOperator(new SetMatrix(this));
        }

        @Override
        protected void processOperator(Operator operator, List<COSBase> operands) throws IOException {
            if ("Do".equals(operator.getName())) {
                COSBase base = operands.isEmpty() ? null : operands.get(0);
                if (base instanceof COSName name) {
                    PDXObject xobject = getResources().getXObject(name);
                    if (xobject instanceof PDImageXObject) {
                        recordPlacement(getGraphicsState().getCurrentTransformationMatrix());
                    } else if (xobject instanceof PDFormXObject form) {
                        showForm(form);
                    }
                }
            } else {
                super.processOperator(operator, operands);
            }
        }

        private void recordPlacement(org.apache.pdfbox.util.Matrix ctm) {
            placements.add(BoundingBox.builder()
                    .x((float) ctm.getTranslateX())
                    .y((float) ctm.getTranslateY())
                    .width((float) Math.abs(ctm.getScalingFactorX()))
                    .height((float) Math.abs(ctm.getScalingFactorY()))
                    .build());
        }
    }

    // ---------- 表格候选（原生文字网格，仅标记） ----------

    private static class PixelStats {
        double redRatio;
        double inkRatio;
        int textBands;
        double textCandidateScore;
    }
}
