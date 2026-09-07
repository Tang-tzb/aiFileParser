package com.aifp.aiagent.parser.pdf.layout;

import com.aifp.aiagent.parser.pdf.region.CoordinateMatcher;
import com.aifp.aiagent.parser.pdf.text.BoundingBox;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.awt.image.BufferedImage;
import java.util.*;

/**
 * 方式一表格识别器：规则型表格线恢复器（阶段 7，纯 Java 像素分析）。
 * <p>
 * <b>定位声明</b>：只承诺"有明显直线表格边框的常规表单/表格"（制式证照类）；
 * 不要求覆盖无边框、扭曲、含复杂视觉元素的表格的所有情况——线检测门槛
 * 不满足时返回空（禁止输出低置信半可靠格网），由 HybridTableRecognizer
 * 回退 {@link CoordinateTableRecognizer}。
 * <p>
 * 算法链路：灰度二值 → 横/竖长直线检测（行程投影）→ 共线合并 → 格网
 * 坐标去重 → 交点密度门槛 → 相邻单元格公共边<b>多段采样 + 多数投票</b>
 * （排除文字/印章/扫描噪声对边线判断的影响）→ 并查集合并 → TableGrid 骨架。
 * <p>
 * 性能边界（2CPU/8GB）：全图单次批量取像素质 + O(W×H) 单遍二值化，
 * 边投票仅对格网边小范围采样；无外部依赖。
 *
 * @author Tang_tzb
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ImageTableRecognizer implements TableStructureRecognizer {

    /**
     * 线段合并：位置容差（px，相邻行/列的线归并）
     */
    private static final float LINE_MERGE_TOLERANCE_PX = 2f;
    /**
     * 线段合并：区间重叠占比下限
     */
    private static final double LINE_MERGE_OVERLAP = 0.80;
    /**
     * 稀疏周框组剔除系数：bbox 面积超过成员面积和该倍数视为周框链（非真实格）
     */
    private static final double RAGGED_BOX_AREA_FACTOR = 1.05;
    private final CoordinateMatcher coordinateMatcher;
    /**
     * 格网有效性：交点存在率下限（防页面标题区误判）。制式表单常见嵌套布局
     * （子框仅占部分跨度）会稀释整格交点率，故默认值低于常规整格场景；
     * 误判风险由 Hybrid 的文字包住率（0.60）+ 值绑定双重门槛兜底。
     */
    @Value("${document.parser.pdf.table.grid-intersection-density-min:0.20}")
    private double intersectionDensityMin = 0.20;
    /**
     * 灰度二值阈值（luminance 低于该值判暗）。取 200 以覆盖<b>浅灰色表格线</b>
     * （真实样例中存在 luminance 160~200 的灰色横线，160 会漏检导致两行并为一行、
     * 值串列）；文字笔画无法形成长行程，不会因阈值放宽而误报为线。
     */
    @Value("${document.parser.pdf.table.dark-threshold:200}")
    private int darkThreshold = 200;
    /**
     * 横线最短行程占页宽比（文字笔画不可达）
     */
    @Value("${document.parser.pdf.table.min-h-line-ratio:0.15}")
    private double minHLineRatio = 0.15;
    /**
     * 竖线最短行程占页高比（防大号标题笔画误报）
     */
    @Value("${document.parser.pdf.table.min-v-line-ratio:0.12}")
    private double minVLineRatio = 0.12;
    /**
     * 共线线段合并间距（pt）
     */
    @Value("${document.parser.pdf.table.line-merge-gap-pt:3.0}")
    private double lineMergeGapPt = 3.0;
    /**
     * 公共边分段的段长（pt，多段投票粒度）
     */
    @Value("${document.parser.pdf.table.edge-segment-pt:16.0}")
    private double edgeSegmentPt = 16.0;
    /**
     * 边存在性判定：单段采样暗点占比
     */
    @Value("${document.parser.pdf.table.edge-verify-ratio:0.70}")
    private double edgeVerifyRatio = 0.70;
    /**
     * 边存在性判定：通过段数占比（多数投票）
     */
    @Value("${document.parser.pdf.table.edge-vote-ratio:0.60}")
    private double edgeVoteRatio = 0.60;
    /**
     * 印章排除：段内红色像素占比阈值（复用 region 配置口径）
     */
    @Value("${document.parser.pdf.region.stamp-red-ratio:0.10}")
    private double stampRedRatio = 0.10;

    /**
     * 红色像素判定（印章特征，与 DefaultRegionAnalyzer 同口径）。
     */
    private static boolean isRed(int rgb) {
        int r = (rgb >> 16) & 0xFF;
        int g = (rgb >> 8) & 0xFF;
        int b = rgb & 0xFF;
        return r > 150 && r - g > 50 && r - b > 50;
    }

    /**
     * 线段区间重叠占比：交集长度 / 较短线段长度。
     */
    private static double spanOverlapRatio(Segment a, Segment b) {
        float lo = Math.max(a.from, b.from);
        float hi = Math.min(a.to, b.to);
        if (hi <= lo) {
            return 0;
        }
        double minLen = Math.min(a.to - a.from, b.to - b.from);
        return minLen <= 0 ? 0 : (hi - lo) / minLen;
    }

    // ---------- 像素预处理 ----------

    /**
     * 线位置升序去重：间距 &lt; gapPx 的相邻位置链式合并（取均值）→ 格网坐标。
     */
    private static float[] mergePositions(List<Segment> lines, float gapPx) {
        List<Float> positions = new ArrayList<>(lines.size());
        for (Segment s : lines) {
            positions.add(s.pos);
        }
        positions.sort(Float::compareTo);
        List<Float> merged = new ArrayList<>();
        float sum = 0;
        int count = 0;
        float last = Float.NaN;
        for (float p : positions) {
            if (count > 0 && p - last < gapPx) {
                sum += p;
                last = p;
                count++;
            } else {
                if (count > 0) {
                    merged.add(sum / count);
                }
                sum = p;
                last = p;
                count = 1;
            }
        }
        if (count > 0) {
            merged.add(sum / count);
        }
        float[] arr = new float[merged.size()];
        for (int i = 0; i < arr.length; i++) {
            arr[i] = merged.get(i);
        }
        return arr;
    }

    /**
     * 距 pos 最近的线段（格网坐标由线位置均值而来，必有邻近线）。
     */
    private static Segment nearest(List<Segment> lines, float pos) {
        Segment best = null;
        double bestDist = Double.MAX_VALUE;
        for (Segment s : lines) {
            double dist = Math.abs(s.pos - pos);
            if (dist < bestDist) {
                bestDist = dist;
                best = s;
            }
        }
        return best;
    }

    private static int clamp(int v, int min, int max) {
        return Math.max(min, Math.min(max, v));
    }

    // ---------- 线检测与共线合并 ----------

    /**
     * 单元格像素框（图像空间 top-left）并入并集。
     */
    private static BoundingBox unionPixelBox(BoundingBox acc, int i, int j, float[] xs, float[] ys) {
        BoundingBox box = BoundingBox.builder()
                .x(xs[j])
                .y(ys[i])
                .width(xs[j + 1] - xs[j])
                .height(ys[i + 1] - ys[i])
                .build();
        return acc == null ? box : acc.union(box);
    }

    private static int cellIndex(int i, int j, int cols) {
        return i * cols + j;
    }

    /**
     * 线检测明细（pt，诊断用）：pos/from/to 均由像素 ÷k 换算。
     */
    private static String describeLines(List<Segment> lines, float[] positions, boolean horizontal,
                                        float k) {
        StringBuilder sb = new StringBuilder("[");
        for (Segment s : lines) {
            if (sb.length() > 1) {
                sb.append(", ");
            }
            sb.append(String.format("pos=%.0f[%.0f..%.0f]", s.pos / k, s.from / k, s.to / k));
        }
        return sb.append("] merged=").append(positions.length).toString();
    }

    @Override
    public List<TableGrid> recognize(TableRecognitionInput input) {
        try {
            TableGrid grid = detectGrid(input);
            return grid == null ? List.of() : List.of(grid);
        } catch (Exception e) {
            // 全异常封闭：识别失败降级为空，由上层回退方式二
            log.warn("图像表格线识别失败降级 pageNumber={}",
                    input == null ? -1 : input.getPageNumber(), e);
            return List.of();
        }
    }

    // ---------- 格网门槛与边投票 ----------

    /**
     * 主流程：二值化 → 线检测 → 格网坐标 → 门槛 → 边投票合并 → 组装。
     */
    private TableGrid detectGrid(TableRecognitionInput input) {
        BufferedImage image = input.getRenderedPage();
        if (image == null || image.getWidth() < 3 || image.getHeight() < 3) {
            return null;
        }
        float k = input.getDpi() / 72f;
        int[] rgb = image.getRGB(0, 0, image.getWidth(), image.getHeight(), null, 0, image.getWidth());
        boolean[][] dark = buildDarkMatrix(rgb, image.getWidth(), image.getHeight());
        List<Segment> hLines = mergeSegments(detectLines(dark, true,
                (int) Math.round(image.getWidth() * minHLineRatio)));
        List<Segment> vLines = mergeSegments(detectLines(dark, false,
                (int) Math.round(image.getHeight() * minVLineRatio)));
        float gapPx = (float) (lineMergeGapPt * k);
        float[] ys = mergePositions(hLines, gapPx);
        float[] xs = mergePositions(vLines, gapPx);
        if (ys.length < 3 || xs.length < 3) {
            log.info("表格线不足（需至少2行2列） pageNumber={}, hLines={}, vLines={}",
                    input.getPageNumber(), hLines.size(), vLines.size());
            return null;
        }
        double density = intersectionDensity(hLines, vLines, ys, xs);
        if (log.isInfoEnabled()) {
            log.info("表格线检测明细 pageNumber={}, hLines={}, vLines={}, density={}",
                    input.getPageNumber(), describeLines(hLines, ys, true, k),
                    describeLines(vLines, xs, false, k), String.format("%.3f", density));
        }
        if (density < intersectionDensityMin) {
            log.info("格网交点密度不足 pageNumber={}, density={}, hLines={}, vLines={}",
                    input.getPageNumber(), density, hLines.size(), vLines.size());
            return null;
        }
        List<TableCell> cells = buildCells(xs, ys, dark, rgb, image, input, k);
        TableGrid grid = assembleGrid(xs, ys, cells, input);
        log.info("图像表格线识别完成 pageNumber={}, grid={}x{}, cells={}",
                input.getPageNumber(), grid.getRowCount(), grid.getColumnCount(), cells.size());
        return grid;
    }

    /**
     * 批量像素 → 暗点矩阵（luminance 阈值二值化，单遍 O(W×H)）。
     */
    private boolean[][] buildDarkMatrix(int[] rgb, int width, int height) {
        boolean[][] dark = new boolean[height][width];
        for (int y = 0; y < height; y++) {
            int row = y * width;
            for (int x = 0; x < width; x++) {
                dark[y][x] = isDark(rgb[row + x]);
            }
        }
        return dark;
    }

    /**
     * 暗点判定：灰度 luminance 低于阈值（实例配置，非静态）。
     */
    private boolean isDark(int rgb) {
        int r = (rgb >> 16) & 0xFF;
        int g = (rgb >> 8) & 0xFF;
        int b = rgb & 0xFF;
        return (r * 299 + g * 587 + b * 114) / 1000 < darkThreshold;
    }

    /**
     * 逐行/逐列单遍扫描：取最长连续暗行程 ≥ minRunPx 记为线段；
     * 按最长段取值（同一行含表格线与文字时，长线胜出、文字笔画不可达门槛）。
     */
    private List<Segment> detectLines(boolean[][] dark, boolean horizontal, int minRunPx) {
        int outer = horizontal ? dark.length : dark[0].length;
        int inner = horizontal ? dark[0].length : dark.length;
        if (minRunPx < 1 || outer == 0 || inner == 0) {
            return List.of();
        }
        List<Segment> segments = new ArrayList<>();
        for (int o = 0; o < outer; o++) {
            int bestStart = -1;
            int bestLen = 0;
            int curStart = -1;
            int curLen = 0;
            for (int i = 0; i < inner; i++) {
                boolean d = horizontal ? dark[o][i] : dark[i][o];
                if (d) {
                    curStart = curStart < 0 ? i : curStart;
                    curLen++;
                    if (curLen > bestLen) {
                        bestLen = curLen;
                        bestStart = curStart;
                    }
                } else {
                    curStart = -1;
                    curLen = 0;
                }
            }
            if (bestLen >= minRunPx) {
                segments.add(new Segment(o, bestStart, bestStart + bestLen));
            }
        }
        return segments;
    }

    /**
     * 相邻线段归并：位置差 ≤ 容差且区间重叠 ≥80% → 一条线
     * （位置取加权均值，区间取并集，抗断线/抗锯齿）。
     */
    private List<Segment> mergeSegments(List<Segment> segments) {
        segments.sort(Comparator.comparingDouble(s -> s.pos));
        List<Segment> merged = new ArrayList<>();
        for (Segment s : segments) {
            Segment target = null;
            for (Segment m : merged) {
                if (Math.abs(m.pos - s.pos) <= LINE_MERGE_TOLERANCE_PX
                        && spanOverlapRatio(m, s) >= LINE_MERGE_OVERLAP) {
                    target = m;
                    break;
                }
            }
            if (target == null) {
                merged.add(new Segment(s.pos, s.from, s.to));
            } else {
                target.absorb(s);
            }
        }
        return merged;
    }

    // ---------- 格网合并与组装 ----------

    /**
     * 交点存在率：横线覆盖 x 且竖线覆盖 y 的 (ys[i], xs[j]) 组合占比。
     */
    private double intersectionDensity(List<Segment> hLines, List<Segment> vLines,
                                       float[] ys, float[] xs) {
        int total = ys.length * xs.length;
        if (total == 0 || hLines.isEmpty() || vLines.isEmpty()) {
            return 0;
        }
        int hits = 0;
        for (float y : ys) {
            Segment h = nearest(hLines, y);
            for (float x : xs) {
                Segment v = nearest(vLines, x);
                if (x >= h.from && x <= h.to && y >= v.from && y <= v.to) {
                    hits++;
                }
            }
        }
        return hits / (double) total;
    }

    /**
     * 公共边存在性：多段采样 + 多数投票。
     * <p>
     * 边切分为长度 ≈ edge-segment-pt 的若干段，每段沿法线 ±1px 共 3 条采样线
     * （任一命中视为该点有墨）；段内暗点占比 ≥ edge-verify-ratio 该段通过；
     * 红色占比 ≥ 印章阈值的段<b>不计入投票</b>（排除印章干扰）。
     * 通过段数/总段数 ≥ edge-vote-ratio 才判"边真实存在"。
     */
    private boolean edgeExists(boolean[][] dark, int[] rgb, int imgW, int imgH,
                               boolean vertical, float pos, float from, float to, float k) {
        int segLenPx = Math.max(1, (int) Math.round(edgeSegmentPt * k));
        int start = Math.max(0, Math.round(from));
        int end = Math.min(vertical ? imgH : imgW, Math.round(to));
        int total = 0;
        int passed = 0;
        for (int s = start; s < end; s += segLenPx) {
            SegmentVote vote = voteSegment(dark, rgb, imgW, imgH, vertical, pos,
                    s, Math.min(end, s + segLenPx));
            if (vote.redRatio() >= stampRedRatio) {
                continue;
            }
            total++;
            if (vote.darkRatio() >= edgeVerifyRatio) {
                passed++;
            }
        }
        return total > 0 && passed / (double) total >= edgeVoteRatio;
    }

    /**
     * 单段采样：沿边法线 ±1px 共 3 条采样线（任一命中视为该点有墨/红色），
     * 返回段内暗点占比与红色占比。
     */
    private SegmentVote voteSegment(boolean[][] dark, int[] rgb, int imgW, int imgH,
                                    boolean vertical, float pos, int segStart, int segEnd) {
        int p = Math.round(pos);
        int darkPoints = 0;
        int redPoints = 0;
        int points = 0;
        for (int t = segStart; t < segEnd; t++) {
            boolean pointDark = false;
            boolean pointRed = false;
            for (int offset = -1; offset <= 1; offset++) {
                int x = vertical ? clamp(p + offset, 0, imgW - 1) : clamp(t, 0, imgW - 1);
                int y = vertical ? clamp(t, 0, imgH - 1) : clamp(p + offset, 0, imgH - 1);
                pointDark |= dark[y][x];
                pointRed |= isRed(rgb[y * imgW + x]);
            }
            darkPoints += pointDark ? 1 : 0;
            redPoints += pointRed ? 1 : 0;
            points++;
        }
        return points == 0
                ? new SegmentVote(0, 1)
                : new SegmentVote(darkPoints / (double) points, redPoints / (double) points);
    }

    /**
     * 相邻单元格公共边逐一投票：边不存在 → 并查集合并（rowSpan/colSpan 来源）。
     */
    private List<TableCell> buildCells(float[] xs, float[] ys, boolean[][] dark, int[] rgb,
                                       BufferedImage image, TableRecognitionInput input, float k) {
        int rows = ys.length - 1;
        int cols = xs.length - 1;
        UnionFind uf = new UnionFind(rows * cols);
        for (int i = 0; i < rows; i++) {
            for (int j = 0; j < cols - 1; j++) {
                if (!edgeExists(dark, rgb, image.getWidth(), image.getHeight(),
                        true, xs[j + 1], ys[i], ys[i + 1], k)) {
                    uf.union(cellIndex(i, j, cols), cellIndex(i, j + 1, cols));
                }
            }
        }
        for (int j = 0; j < cols; j++) {
            for (int i = 0; i < rows - 1; i++) {
                if (!edgeExists(dark, rgb, image.getWidth(), image.getHeight(),
                        false, ys[i + 1], xs[j], xs[j + 1], k)) {
                    uf.union(cellIndex(i, j, cols), cellIndex(i + 1, j, cols));
                }
            }
        }
        return groupCells(uf, rows, cols, xs, ys, input);
    }

    /**
     * 并查集分组 → 合并单元格：rowIndex/columnIndex 取组内最小，
     * span 取跨度，bbox 取成员像素框并集（→ PDF 用户空间）。
     * <p>
     * <b>稀疏周框组拆散</b>：合法合并单元格必为连续矩形（bbox 面积 = 成员
     * 面积和）；并查集链可能把"沟列/顶底行"连成覆盖全表的稀疏周框（其
     * bbox 吞掉真实格并干扰值绑定）——bbox 面积 &gt; 1.05×成员面积和的组
     * <b>拆散为成员 1×1 单元格</b>（不整组丢弃，避免误伤组内真实格）。
     */
    private List<TableCell> groupCells(UnionFind uf, int rows, int cols, float[] xs, float[] ys,
                                       TableRecognitionInput input) {
        Map<Integer, List<int[]>> groups = new LinkedHashMap<>();
        for (int i = 0; i < rows; i++) {
            for (int j = 0; j < cols; j++) {
                groups.computeIfAbsent(uf.find(cellIndex(i, j, cols)), key -> new ArrayList<>())
                        .add(new int[]{i, j});
            }
        }
        List<TableCell> cells = new ArrayList<>(groups.size());
        for (List<int[]> members : groups.values()) {
            int minI = members.get(0)[0];
            int maxI = minI;
            int minJ = members.get(0)[1];
            int maxJ = minJ;
            BoundingBox pixelBox = null;
            double memberAreaSum = 0;
            for (int[] m : members) {
                minI = Math.min(minI, m[0]);
                maxI = Math.max(maxI, m[0]);
                minJ = Math.min(minJ, m[1]);
                maxJ = Math.max(maxJ, m[1]);
                memberAreaSum += (xs[m[1] + 1] - xs[m[1]]) * (ys[m[0] + 1] - ys[m[0]]);
                pixelBox = unionPixelBox(pixelBox, m[0], m[1], xs, ys);
            }
            double boxArea = (double) pixelBox.getWidth() * pixelBox.getHeight();
            if (boxArea > memberAreaSum * RAGGED_BOX_AREA_FACTOR) {
                log.info("拆散稀疏周框合并组 rows={}x{} members={}", maxI - minI + 1,
                        maxJ - minJ + 1, members.size());
                for (int[] m : members) {
                    cells.add(memberCell(m, xs, ys, input));
                }
                continue;
            }
            cells.add(TableCell.builder()
                    .rowIndex(minI)
                    .columnIndex(minJ)
                    .rowSpan(maxI - minI + 1)
                    .colSpan(maxJ - minJ + 1)
                    .boundingBox(coordinateMatcher.toPdfBox(pixelBox, input.getDpi(), input.getPageHeight()))
                    .build());
        }
        cells.sort(Comparator.comparingInt(TableCell::getRowIndex)
                .thenComparingInt(TableCell::getColumnIndex));
        return cells;
    }

    /**
     * 单个骨架格 → 1×1 单元格（稀疏组拆散用）。
     */
    private TableCell memberCell(int[] m, float[] xs, float[] ys, TableRecognitionInput input) {
        return TableCell.builder()
                .rowIndex(m[0])
                .columnIndex(m[1])
                .rowSpan(1)
                .colSpan(1)
                .boundingBox(coordinateMatcher.toPdfBox(
                        BoundingBox.builder()
                                .x(xs[m[1]]).y(ys[m[0]])
                                .width(xs[m[1] + 1] - xs[m[1]])
                                .height(ys[m[0] + 1] - ys[m[0]])
                                .build(),
                        input.getDpi(), input.getPageHeight()))
                .build();
    }

    /**
     * 组装格网骨架：grid bbox = 单元格并集；行 bbox = 格网整行（像素→PDF）。
     */
    private TableGrid assembleGrid(float[] xs, float[] ys, List<TableCell> cells,
                                   TableRecognitionInput input) {
        BoundingBox gridBbox = null;
        for (TableCell cell : cells) {
            gridBbox = gridBbox == null ? cell.getBoundingBox() : gridBbox.union(cell.getBoundingBox());
        }
        List<TableRow> rows = new ArrayList<>(ys.length - 1);
        for (int r = 0; r < ys.length - 1; r++) {
            final int rowIndex = r;
            BoundingBox rowPixel = BoundingBox.builder()
                    .x(xs[0])
                    .y(ys[r])
                    .width(xs[xs.length - 1] - xs[0])
                    .height(ys[r + 1] - ys[r])
                    .build();
            BoundingBox rowPdf = coordinateMatcher.toPdfBox(rowPixel, input.getDpi(), input.getPageHeight());
            List<TableCell> rowCells = cells.stream()
                    .filter(c -> c.getRowIndex() == rowIndex)
                    .toList();
            rows.add(TableRow.builder().index(rowIndex).bbox(rowPdf).cells(rowCells).build());
        }
        return TableGrid.builder()
                .pageNumber(input.getPageNumber())
                .bbox(gridBbox)
                .rowCount(ys.length - 1)
                .columnCount(xs.length - 1)
                .rows(rows)
                .cells(cells)
                .build();
    }

    /**
     * 线段：pos = 线位置（横线 y / 竖线 x，px），[from, to) = 行程区间；
     * absorb 做位置加权均值 + 区间并集（共线归并）。
     */
    private static final class Segment {
        private float pos;
        private float from;
        private float to;
        private float posSum;
        private int count;

        private Segment(float pos, float from, float to) {
            this.pos = pos;
            this.posSum = pos;
            this.count = 1;
            this.from = from;
            this.to = to;
        }

        private void absorb(Segment other) {
            posSum += other.pos;
            count++;
            pos = posSum / count;
            from = Math.min(from, other.from);
            to = Math.max(to, other.to);
        }
    }

    /**
     * 单段投票结果。
     */
    private record SegmentVote(double darkRatio, double redRatio) {
    }

    /**
     * 轻量并查集（格网单元格合并）。
     */
    private static final class UnionFind {
        private final int[] parent;

        private UnionFind(int n) {
            parent = new int[n];
            for (int i = 0; i < n; i++) {
                parent[i] = i;
            }
        }

        private int find(int x) {
            return parent[x] == x ? x : (parent[x] = find(parent[x]));
        }

        private void union(int a, int b) {
            parent[find(a)] = find(b);
        }
    }
}
