package com.aifp.aiagent.parser.pdf.region;

import com.aifp.aiagent.parser.pdf.text.BoundingBox;
import com.aifp.aiagent.parser.pdf.text.CoordinateTransformer;
import com.aifp.aiagent.parser.pdf.text.TextBlock;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 坐标匹配器：MIXED 页"坐标统一 + 词覆盖率去重 + 空间合法性校验"。
 * <p>
 * 坐标换算全部委托 {@link CoordinateTransformer}（不自建公式）。
 * <p>
 * <b>去重参数语义（重要）</b>：word-dedup-ratio 是"OCR 词面积被 PDF 原生文字
 * 覆盖的比例阈值"（主判定 = 词与全部文字块交集的<b>并集</b>面积 / 词面积），
 * <b>不是 IoU 阈值</b>——OCR 词 bbox 通常比 PDF TextBlock 更紧，中文场景下一个
 * TextBlock 常覆盖多个 OCR 词，IoU 会系统性偏低，故仅作辅助指标
 * （IoU + 词中心点包含）。
 *
 * @author Tang_tzb
 */
@Component
@RequiredArgsConstructor
public class CoordinateMatcher {

    /**
     * 辅助去重：IoU 阈值（仅辅助，主判定见 wordCoverageRatio）
     */
    private static final double AUX_IOU_THRESHOLD = 0.8;
    private final CoordinateTransformer coordinateTransformer;
    /**
     * 主去重阈值：OCR 词面积被 PDF 文字覆盖的比例（非 IoU，见类注释）
     */
    @Value("${document.parser.pdf.region.word-dedup-ratio:0.80}")
    private double wordDedupRatio = 0.80;
    /**
     * 空间合法性容差（pt）：区域 bbox 外扩该值后需与元素存在有效交集
     */
    @Value("${document.parser.pdf.region.spatial-tolerance-pt:2.0}")
    private double spatialTolerancePt = 2.0;

    /**
     * 交集并集面积：source 与 boxes 的全部交集做增量并集后求面积
     * （块间重叠不重复计面积，调用方按需封顶）。
     */
    private static double unionAreaOfIntersections(BoundingBox source, List<BoundingBox> boxes) {
        List<BoundingBox> parts = new ArrayList<>(boxes.size());
        for (BoundingBox box : boxes) {
            BoundingBox inter = source.intersection(box);
            if (inter != null) {
                mergeInto(inter, parts);
            }
        }
        double total = 0;
        for (BoundingBox part : parts) {
            total += (double) part.getWidth() * part.getHeight();
        }
        return total;
    }

    /**
     * 交叠的矩形合并后再入列（增量并集，矩形数量少，二次复杂度可接受）。
     */
    private static void mergeInto(BoundingBox box, List<BoundingBox> parts) {
        for (int i = 0; i < parts.size(); i++) {
            if (parts.get(i).overlap(box)) {
                parts.set(i, parts.get(i).union(box));
                // 合并后可能产生新的可合并项，重扫一遍
                BoundingBox merged = parts.remove(i);
                mergeInto(merged, parts);
                return;
            }
        }
        parts.add(box);
    }

    /**
     * 区域文字覆盖率：文字块与区域交集并集面积 / 区域面积（封顶 1）。
     */
    public double coverageRatio(BoundingBox region, List<TextBlock> textBlocks) {
        if (region == null || region.getWidth() * region.getHeight() <= 0) {
            return 0d;
        }
        List<BoundingBox> boxes = new ArrayList<>(textBlocks.size());
        for (TextBlock block : textBlocks) {
            boxes.add(block.getBbox());
        }
        double covered = unionAreaOfIntersections(region, boxes);
        return Math.min(1d, covered / ((double) region.getWidth() * region.getHeight()));
    }

    /**
     * 词覆盖率（主去重判定）：OCR 词与全部文字块交集的并集面积 / 词面积。
     * 语义 = "该 OCR 词有多少面积已被 PDF 原生文字覆盖"。
     */
    public double wordCoverageRatio(BoundingBox wordPdfBox, List<TextBlock> textBlocks) {
        if (wordPdfBox == null || wordPdfBox.getWidth() * wordPdfBox.getHeight() <= 0) {
            return 0d;
        }
        List<BoundingBox> boxes = new ArrayList<>(textBlocks.size());
        for (TextBlock block : textBlocks) {
            boxes.add(block.getBbox());
        }
        double covered = unionAreaOfIntersections(wordPdfBox, boxes);
        return Math.min(1d, covered / ((double) wordPdfBox.getWidth() * wordPdfBox.getHeight()));
    }

    /**
     * OCR 词是否与 PDF 原生文字重复：
     * 主判定 = 词覆盖率 ≥ word-dedup-ratio；辅助 = 与某文字块 IoU ≥ 0.8 或词中心点落在块内。
     */
    public boolean isDuplicateWord(BoundingBox wordPdfBox, List<TextBlock> textBlocks) {
        if (wordCoverageRatio(wordPdfBox, textBlocks) >= wordDedupRatio) {
            return true;
        }
        float centerX = wordPdfBox.getX() + wordPdfBox.getWidth() / 2f;
        float centerY = wordPdfBox.getY() + wordPdfBox.getHeight() / 2f;
        for (TextBlock block : textBlocks) {
            BoundingBox bbox = block.getBbox();
            if (wordPdfBox.iou(bbox) >= AUX_IOU_THRESHOLD || bbox.contains(centerX, centerY)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 空间合法性：元素 bbox 与区域 bbox（外扩容差）存在有效交集（面积 > 0）。
     */
    public boolean isWithinRegion(BoundingBox elementBbox, BoundingBox regionBbox) {
        if (elementBbox == null || regionBbox == null) {
            return false;
        }
        BoundingBox expanded = regionBbox.expand((float) spatialTolerancePt);
        return elementBbox.intersection(expanded) != null;
    }

    /**
     * PDF 用户空间框 → 渲染图像素裁剪范围（整型、钳制到图像边界，宽度/高度至少 1px）。
     * 返回 int[]{x, y, width, height}（top-left 原点）。
     */
    public int[] pixelCropBounds(BoundingBox pdfBox, float dpi, float pageHeight,
                                 int imageWidth, int imageHeight) {
        BoundingBox pixel = coordinateTransformer.pdfToImage(pdfBox, dpi, pageHeight);
        int x = Math.max(0, (int) Math.floor(pixel.getX()));
        int y = Math.max(0, (int) Math.floor(pixel.getY()));
        int x1 = Math.min(imageWidth, (int) Math.ceil(pixel.right()));
        int y1 = Math.min(imageHeight, (int) Math.ceil(pixel.top()));
        return new int[]{x, y, Math.max(1, x1 - x), Math.max(1, y1 - y)};
    }

    /**
     * 像素框 → PDF 用户空间框（委托换算）。
     */
    public BoundingBox toPdfBox(BoundingBox pixelBox, float dpi, float pageHeight) {
        return coordinateTransformer.imageToPdf(pixelBox, dpi, pageHeight);
    }
}
