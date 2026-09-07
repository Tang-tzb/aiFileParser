package com.aifp.aiagent.parser.pdf.text;

import com.aifp.aiagent.common.ResultCode;
import com.aifp.aiagent.exception.BusinessException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.text.TextPosition;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.*;

/**
 * 默认结构化文字提取器。
 * <p>
 * 聚合算法（阶段 3 要求）：
 * <ol>
 *   <li>采集：PDFTextStripper 子类收集页内全部 {@link TextPosition}，经
 *       {@link PdfCoordinateConverter} 归一为 PDF 用户空间字形 box；</li>
 *   <li>Y 聚合成行：按字形中心 Y 排序，中心距 ≤ lineToleranceRatio × 行内最大字高归入同行；</li>
 *   <li>行内 X 排序：按 bbox.x 升序重建行文字，几何间隙 > spaceGapRatio × 字号处插空格；</li>
 *   <li>多行聚合成块：行间距 > blockGapRatio × 前行字号则分块，块外接矩形取各行并集。</li>
 * </ol>
 * 已知局限见 {@link PdfCoordinateConverter}（旋转页近似）。
 *
 * @author Tang_tzb
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DefaultPdfTextExtractor implements PdfTextExtractor {

    private final PdfCoordinateConverter converter;
    /**
     * Y 聚合成行的容差系数（×行内最大字高；声明处默认值供离线直构使用，容器加载后由配置覆盖）
     */
    @Value("${document.parser.pdf.text.line-tolerance-ratio:0.50}")
    private double lineToleranceRatio = 0.50;
    /**
     * 行内插空格的间隙系数（×字号；Helvetica 空格步进 0.278em，需小于该值方可还原空格）
     */
    @Value("${document.parser.pdf.text.space-gap-ratio:0.25}")
    private double spaceGapRatio = 0.25;
    /**
     * 行内分段的间隙系数（×字号；大于该值的几何间隙视为跨单元格边界——
     * 制式表单同一视觉行横跨多列时按段切分，段为阶段 7 值绑定的原子单位；
     * 取 2.0 远大于词间空格（约 0.3~1.0×字号），不会拆散字段内词语）
     */
    @Value("${document.parser.pdf.text.segment-gap-ratio:2.0}")
    private double segmentGapRatio = 2.0;
    /**
     * 行间距超过该系数×前行字号则分块
     */
    @Value("${document.parser.pdf.text.block-gap-ratio:1.20}")
    private double blockGapRatio = 1.20;

    @Override
    public PdfText extract(PDDocument document, int pageIndex) {
        float pageHeight = document.getPage(pageIndex).getMediaBox().getHeight();
        List<Glyph> glyphs = collectGlyphs(document, pageIndex, pageHeight);

        List<TextLine> lines = buildLines(glyphs);
        List<TextBlock> blocks = buildBlocks(lines, pageIndex + 1);
        return PdfText.builder()
                .pageNumber(pageIndex + 1)
                .blocks(blocks)
                .build();
    }

    /**
     * 采集页内全部有效字形（跳过空白字符），坐标归一为用户空间。
     */
    private List<Glyph> collectGlyphs(PDDocument document, int pageIndex, float pageHeight) {
        try {
            PositionCollector collector = new PositionCollector();
            collector.setStartPage(pageIndex + 1);
            collector.setEndPage(pageIndex + 1);
            collector.getText(document);

            List<Glyph> glyphs = new ArrayList<>(collector.positions.size());
            for (TextPosition pos : collector.positions) {
                String unicode = pos.getUnicode();
                if (unicode == null || unicode.isBlank()) {
                    continue;
                }
                glyphs.add(new Glyph(
                        unicode,
                        converter.toUserSpace(pos, pageHeight),
                        pos.getFontSizeInPt(),
                        pos.getFont() == null ? "" : pos.getFont().getName()));
            }
            return glyphs;
        } catch (IOException e) {
            throw new BusinessException(ResultCode.FILE_PARSE_ERROR,
                    "PDF 文字结构化提取失败: 第" + (pageIndex + 1) + "页");
        }
    }

    /**
     * Y 聚合成行：自上而下聚类（用户空间 y 向上，故按中心 Y 降序 = 阅读顺序）。
     */
    private List<TextLine> buildLines(List<Glyph> glyphs) {
        if (glyphs.isEmpty()) {
            return List.of();
        }
        List<Glyph> sorted = glyphs.stream()
                .sorted(Comparator.comparingDouble((Glyph g) -> centerY(g.box)).reversed())
                .toList();

        List<List<Glyph>> clusters = new ArrayList<>();
        List<Glyph> current = new ArrayList<>();
        double clusterCenter = 0;
        double maxHeight = 0;
        for (Glyph glyph : sorted) {
            if (current.isEmpty()) {
                clusterCenter = centerY(glyph.box);
                maxHeight = glyph.box.getHeight();
            } else {
                double c = centerY(glyph.box);
                if (Math.abs(c - clusterCenter) > lineToleranceRatio * maxHeight) {
                    clusters.add(current);
                    current = new ArrayList<>();
                    clusterCenter = c;
                    maxHeight = glyph.box.getHeight();
                } else {
                    // 滚动更新聚类中心，提升多字形行的稳定性
                    clusterCenter = (clusterCenter * current.size() + c) / (current.size() + 1);
                    maxHeight = Math.max(maxHeight, glyph.box.getHeight());
                }
            }
            current.add(glyph);
        }
        if (!current.isEmpty()) {
            clusters.add(current);
        }
        return clusters.stream().map(this::buildLine).toList();
    }

    /**
     * 行内 X 排序重建行文字：几何间隙超过阈值处插入空格；
     * 并按大几何间隙（&gt; segmentGapRatio × 字号）切分行内段——
     * 制式表单同一视觉行常横跨多个表格单元格，段为表格值绑定的原子单位。
     * 行文字 = 各段文字以空格连接（与逐字形插空格口径逐字节一致）。
     */
    private TextLine buildLine(List<Glyph> cluster) {
        List<Glyph> sorted = cluster.stream()
                .sorted(Comparator.comparingDouble(g -> g.box.getX()))
                .toList();

        // 先按大间隙切段（段内再按小间隙插空格）
        List<List<Glyph>> segmentGlyphs = new ArrayList<>();
        List<Glyph> current = new ArrayList<>();
        for (Glyph glyph : sorted) {
            if (!current.isEmpty()) {
                Glyph prev = current.get(current.size() - 1);
                double gap = glyph.box.getX() - prev.box.right();
                if (gap > segmentGapRatio * prev.fontSize) {
                    segmentGlyphs.add(current);
                    current = new ArrayList<>();
                }
            }
            current.add(glyph);
        }
        if (!current.isEmpty()) {
            segmentGlyphs.add(current);
        }

        List<TextLine.Segment> segments = new ArrayList<>(segmentGlyphs.size());
        StringBuilder text = new StringBuilder();
        for (List<Glyph> segment : segmentGlyphs) {
            if (!text.isEmpty()) {
                text.append(' ');
            }
            String segmentText = joinGlyphText(segment);
            text.append(segmentText);
            segments.add(new TextLine.Segment(segmentText, unionOfGlyphs(segment)));
        }

        FontKey dominant = dominantFontOfGlyphs(sorted);
        return TextLine.builder()
                .text(text.toString())
                .bbox(unionOfGlyphs(sorted))
                .fontSize(dominant.fontSize())
                .fontName(dominant.fontName())
                .segments(segments)
                .build();
    }

    /**
     * 段内文字重建：几何间隙超过 spaceGapRatio × 字号处插入空格
     * （与历史行文字口径一致，保证 content 契约逐字节不变）。
     */
    private String joinGlyphText(List<Glyph> glyphs) {
        StringBuilder text = new StringBuilder();
        Glyph prev = null;
        for (Glyph glyph : glyphs) {
            if (prev != null) {
                double gap = glyph.box.getX() - prev.box.right();
                if (gap > spaceGapRatio * prev.fontSize) {
                    text.append(' ');
                }
            }
            text.append(glyph.text);
            prev = glyph;
        }
        return text.toString();
    }

    /**
     * 多行聚合成块：行间距超过 blockGapRatio × 前行字号则开新块。
     */
    private List<TextBlock> buildBlocks(List<TextLine> lines, int pageNumber) {
        List<TextBlock> blocks = new ArrayList<>();
        List<TextLine> current = new ArrayList<>();
        for (TextLine line : lines) {
            if (!current.isEmpty()) {
                TextLine prev = current.get(current.size() - 1);
                // 用户空间 y 向上：prev 在页面上方，向下的行间空白 = prev.y − line.top()
                double gap = prev.getBbox().getY() - line.getBbox().top();
                if (gap > blockGapRatio * prev.getFontSize()) {
                    blocks.add(mergeBlock(current, pageNumber));
                    current = new ArrayList<>();
                }
            }
            current.add(line);
        }
        if (!current.isEmpty()) {
            blocks.add(mergeBlock(current, pageNumber));
        }
        return blocks;
    }

    /**
     * 合并行为块：块文字按行 \n 连接，bbox 取并集，主字体按行字符数加权。
     */
    private TextBlock mergeBlock(List<TextLine> lines, int pageNumber) {
        BoundingBox bbox = lines.get(0).getBbox();
        for (int i = 1; i < lines.size(); i++) {
            bbox = bbox.union(lines.get(i).getBbox());
        }
        String text = lines.stream()
                .map(TextLine::getText)
                .reduce((a, b) -> a + "\n" + b)
                .orElse("");

        FontKey dominant = dominantFontOfLines(lines);
        return TextBlock.builder()
                .text(text)
                .lines(List.copyOf(lines))
                .bbox(bbox)
                .fontSize(dominant.fontSize())
                .fontName(dominant.fontName())
                .page(pageNumber)
                .build();
    }

    /**
     * 字形级主字体统计（按字符数）。
     */
    private FontKey dominantFontOfGlyphs(List<Glyph> glyphs) {
        Map<FontKey, Integer> counts = new LinkedHashMap<>();
        for (Glyph glyph : glyphs) {
            counts.merge(new FontKey(glyph.fontName, glyph.fontSize), glyph.text.length(), Integer::sum);
        }
        return bestFont(counts);
    }

    /**
     * 行级主字体统计（按行文字长度加权）。
     */
    private FontKey dominantFontOfLines(List<TextLine> lines) {
        Map<FontKey, Integer> counts = new LinkedHashMap<>();
        for (TextLine line : lines) {
            counts.merge(new FontKey(line.getFontName(), line.getFontSize()), line.getText().length(), Integer::sum);
        }
        return bestFont(counts);
    }

    private FontKey bestFont(Map<FontKey, Integer> counts) {
        return counts.entrySet().stream()
                .max(Map.Entry.comparingByValue())
                .map(Map.Entry::getKey)
                .orElse(new FontKey("", 0f));
    }

    private BoundingBox unionOfGlyphs(List<Glyph> glyphs) {
        BoundingBox result = glyphs.get(0).box;
        for (int i = 1; i < glyphs.size(); i++) {
            result = result.union(glyphs.get(i).box);
        }
        return result;
    }

    private double centerY(BoundingBox box) {
        return box.getY() + box.getHeight() / 2.0;
    }

    /**
     * 字形内部载体：字符 + 用户空间 box + 字体信息。
     */
    private record Glyph(String text, BoundingBox box, float fontSize, String fontName) {
    }

    /**
     * 字体统计键（record 自带 equals/hashCode）。
     */
    private record FontKey(String fontName, float fontSize) {
    }

    /**
     * 位置收集器：仅收集 TextPosition，行/块聚合由本类自行完成。
     */
    private static class PositionCollector extends PDFTextStripper {
        private final List<TextPosition> positions = new ArrayList<>();

        PositionCollector() throws IOException {
            setSortByPosition(true);
        }

        @Override
        protected void writeString(String text, List<TextPosition> textPositions) {
            positions.addAll(textPositions);
        }
    }
}
