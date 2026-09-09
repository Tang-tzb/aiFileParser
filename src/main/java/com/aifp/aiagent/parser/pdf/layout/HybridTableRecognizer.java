package com.aifp.aiagent.parser.pdf.layout;

import com.aifp.aiagent.parser.ocr.OcrParser;
import com.aifp.aiagent.parser.ocr.OcrRequest;
import com.aifp.aiagent.parser.ocr.OcrResult;
import com.aifp.aiagent.parser.ocr.OcrWord;
import com.aifp.aiagent.parser.pdf.page.ElementSource;
import com.aifp.aiagent.parser.pdf.region.CoordinateMatcher;
import com.aifp.aiagent.parser.pdf.text.BoundingBox;
import com.aifp.aiagent.parser.pdf.text.TextBlock;
import com.aifp.aiagent.parser.pdf.text.TextLine;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import javax.imageio.ImageIO;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 混合表格识别编排器（阶段 7，Spring 装配入口，对应《PDF解析改造方案》§九/§十）。
 * <p>
 * 编排链路：图像格网优先（方式一）→ 文字包住率校验 → 不达标回退坐标聚类
 * （方式二）→ <b>值绑定（覆盖率主判定 + 中心点辅助，块级整体绑定 → 行级降级）</b>
 * → {@link HeaderCandidateDetector} 表头候选 → 仅候选单元格裁剪 OCR
 * （FAILED/EMPTY 优雅降级 header=null）→ 表头-值绑定（FUSION）→ 空骨架过滤。
 * <p>
 * 关键语义：
 * <ul>
 *   <li>每个 TextBlock/行只归属一个 cell（取覆盖率最大者）——从机制上防止相邻字段串列；</li>
 *   <li>多行 TextBlock 经块级整体绑定完整进入同一 Cell（"建设地点一条 Cell"）；</li>
 *   <li>值单元格 source=PDF_TEXT/confidence=1.0；纯表头 source=OCR/confidence=表头置信度；
 *       表头-值绑定后 source=FUSION/confidence=0.5+0.5×表头置信度；</li>
 *   <li>整网格价值判定：≥1 个值单元格才输出 TableGrid。</li>
 * </ul>
 * 异常约定：recognize 全异常封闭（降级空列表，不中断解析）。
 * <p>
 * {@code @Primary}：按类型注入 {@link TableStructureRecognizer} 时的生产默认——
 * 本类是 {@link ImageTableRecognizer}/{@link CoordinateTableRecognizer} 的组合编排器，
 * MixedPageParser 等消费者的唯一注入目标（消除三实现歧义）。
 *
 * @author Tang_tzb
 */
@Slf4j
@Primary
@Component
@RequiredArgsConstructor
public class HybridTableRecognizer implements TableStructureRecognizer {

    private final ImageTableRecognizer imageTableRecognizer;
    private final CoordinateTableRecognizer coordinateTableRecognizer;
    private final HeaderCandidateDetector headerCandidateDetector;
    private final CoordinateMatcher coordinateMatcher;
    private final OcrParser ocrParser;

    /**
     * 图像格网有效性：原生文字行包住率
     */
    @Value("${document.parser.pdf.table.grid-text-containment-ratio:0.60}")
    private double gridTextContainmentRatio = 0.60;
    /**
     * 值归属主判定：文字块被单元格覆盖面积占比
     */
    @Value("${document.parser.pdf.table.value-coverage-ratio:0.60}")
    private double valueCoverageRatio = 0.60;
    /**
     * 中心点辅助的最低覆盖率：段级兜底仅允许"轻微出格"（覆盖率 ≥ 该值且
     * 中心点落在覆盖率最高单元格内）。跨格合并行的拦截由段级切分
     * （segment-gap-ratio）与块级防串列守卫承担，本阈值仅约束最后一个
     * 原子段的归属——低于该值的跨格文字宁可不绑定，也不违反
     * "相邻字段不得串列"硬性约束
     */
    @Value("${document.parser.pdf.table.center-fallback-min-coverage:0.50}")
    private double centerFallbackMinCoverage = 0.50;
    /**
     * 表头 OCR 开关（失败自动降级 header=null）
     */
    @Value("${document.parser.pdf.table.header-ocr-enabled:true}")
    private boolean headerOcrEnabled = true;
    /**
     * 表头 OCR 置信度下限：低于该值视为无效识别（竖排文字/图形产出垃圾词，
     * 真实样例竖排列 conf=0.00），降级 header=null 且不参与 FUSION 绑定
     */
    @Value("${document.parser.pdf.table.header-min-confidence:0.60}")
    private double headerMinConfidence = 0.60;

    private static TableGrid firstGrid(List<TableGrid> grids) {
        return grids == null || grids.isEmpty() ? null : grids.get(0);
    }

    /**
     * 行 bbox 中心点是否落入格网任一单元格。
     */
    private static boolean containsAnyCell(TableGrid grid, BoundingBox lineBox) {
        if (lineBox == null || lineBox.getWidth() * lineBox.getHeight() <= 0) {
            return false;
        }
        float centerX = lineBox.getX() + lineBox.getWidth() / 2f;
        float centerY = lineBox.getY() + lineBox.getHeight() / 2f;
        for (TableCell cell : grid.getCells()) {
            if (cell.getBoundingBox().contains(centerX, centerY)) {
                return true;
            }
        }
        return false;
    }

    // ---------- 格网择优 ----------

    /**
     * 行绑定单元：段为原子（旧构造点未赋值 segments 时整行兜底为单段）。
     */
    private static List<TextPiece> piecesOf(TextLine line) {
        if (line.getSegments() == null || line.getSegments().isEmpty()) {
            return List.of(new TextPiece(line.getText(), line.getBbox()));
        }
        return line.getSegments().stream()
                .map(segment -> new TextPiece(segment.text(), segment.bbox()))
                .toList();
    }

    /**
     * 绑定文字按 y 顶→底以 \n 连接（多行自然并入同一单元格）。
     */
    private static String joinTopDown(List<TextPiece> pieces) {
        List<TextPiece> sorted = new ArrayList<>(pieces);
        sorted.sort(Comparator.comparingDouble((TextPiece p) -> p.bbox().getY()).reversed());
        return sorted.stream().map(TextPiece::text).collect(Collectors.joining("\n"));
    }

    /**
     * 值单元格的最近表头：行区间重叠、列在值单元格左侧（header 尾列 ≤ 值首列），
     * 取列序最大者（两列表单即左邻列）。
     */
    private static TableCell nearestHeader(TableCell valueCell, List<TableCell> headerCells) {
        TableCell nearest = null;
        int nearestRight = Integer.MIN_VALUE;
        for (TableCell header : headerCells) {
            boolean rowOverlap = header.getRowIndex() < valueCell.getRowIndex() + valueCell.getRowSpan()
                    && valueCell.getRowIndex() < header.getRowIndex() + header.getRowSpan();
            int rightColumn = header.getColumnIndex() + header.getColSpan();
            if (!rowOverlap || rightColumn > valueCell.getColumnIndex()) {
                continue;
            }
            if (rightColumn > nearestRight) {
                nearest = header;
                nearestRight = rightColumn;
            }
        }
        return nearest;
    }

    /**
     * 过滤空骨架单元格（无值且无 header 不产出，保留格网尺寸语义）。
     */
    private static TableGrid filterGrid(TableGrid grid) {
        List<TableCell> kept = grid.getCells().stream()
                .filter(cell -> cell.getValue() != null || cell.getHeader() != null)
                .toList();
        List<TableRow> rows = grid.getRows().stream()
                .map(row -> TableRow.builder()
                        .index(row.getIndex())
                        .bbox(row.getBbox())
                        .cells(kept.stream()
                                .filter(cell -> cell.getRowIndex() == row.getIndex())
                                .toList())
                        .build())
                .toList();
        return TableGrid.builder()
                .pageNumber(grid.getPageNumber())
                .bbox(grid.getBbox())
                .rowCount(grid.getRowCount())
                .columnCount(grid.getColumnCount())
                .rows(rows)
                .cells(kept)
                .build();
    }

    // ---------- 值绑定（覆盖率主判定 + 中心点辅助） ----------

    /**
     * 写出表头裁剪临时 PNG（与 MixedPageParser 区域裁剪同模式，各自保留小方法）。
     */
    private static File writeCropTempPng(java.awt.image.BufferedImage image, int[] crop)
            throws IOException {
        java.awt.image.BufferedImage subImage =
                image.getSubimage(crop[0], crop[1], crop[2], crop[3]);
        File tempFile = Files.createTempFile("aifp-header-ocr-", ".png").toFile();
        ImageIO.write(subImage, "png", tempFile);
        return tempFile;
    }

    /**
     * 删除临时文件（即用即删）。
     */
    private static void deleteQuietly(File file) {
        if (file == null) {
            return;
        }
        try {
            Files.deleteIfExists(file.toPath());
        } catch (IOException e) {
            log.warn("表头裁剪临时文件删除失败: {}", file, e);
        }
    }

    @Override
    public List<TableGrid> recognize(TableRecognitionInput input) {
        try {
            return doRecognize(input);
        } catch (Exception e) {
            // 全异常封闭：表格识别失败降级为空，解析不中断
            log.warn("表格结构识别异常降级 pageNumber={}",
                    input == null ? -1 : input.getPageNumber(), e);
            return List.of();
        }
    }

    /**
     * 编排主流程：择优格网 → 值绑定 → 表头候选/OCR/绑定 → 过滤输出。
     */
    private List<TableGrid> doRecognize(TableRecognitionInput input) {
        TableGrid grid = selectGrid(input);
        if (grid == null) {
            return List.of();
        }
        if (!bindValues(grid, input.getTextBlocks())) {
            log.info("表格无绑定值，不输出 pageNumber={}", input.getPageNumber());
            return List.of();
        }
        bindHeaders(grid, input);
        TableGrid output = filterGrid(grid);
        log.info("表格结构恢复完成 pageNumber={}, 骨架={}x{}, 输出单元格={}",
                input.getPageNumber(), output.getRowCount(), output.getColumnCount(),
                output.getCells().size());
        return List.of(output);
    }

    /**
     * 图像格网有效（≥2×2）且文字包住率达标 → 采用方式一；否则回退方式二。
     */
    private TableGrid selectGrid(TableRecognitionInput input) {
        TableGrid imageGrid = firstGrid(imageTableRecognizer.recognize(input));
        if (imageGrid != null) {
            double containment = textContainment(imageGrid, input.getTextBlocks());
            if (containment >= gridTextContainmentRatio) {
                log.info("采用图像格网 pageNumber={}, containment={}",
                        input.getPageNumber(), containment);
                return imageGrid;
            }
            log.info("图像格网文字包住率不足，回退坐标聚类 pageNumber={}, containment={}",
                    input.getPageNumber(), containment);
        }
        return firstGrid(coordinateTableRecognizer.recognize(input));
    }

    // ---------- 表头候选 / OCR / 表头-值绑定 ----------

    /**
     * 文字包住率：原生文字行中心点落入格网任一单元格的比例。
     */
    private double textContainment(TableGrid grid, List<TextBlock> textBlocks) {
        long total = 0;
        long contained = 0;
        for (TextBlock block : textBlocks == null ? List.<TextBlock>of() : textBlocks) {
            for (TextLine line : block.getLines()) {
                total++;
                if (containsAnyCell(grid, line.getBbox())) {
                    contained++;
                }
            }
        }
        return total == 0 ? 0 : contained / (double) total;
    }

    /**
     * 值绑定：块级整体判定优先（跨行/多行 TextBlock 完整绑定），块级不达标
     * <b>或块内段路由不一致</b>时降为行内<b>段级</b>（{@link TextLine.Segment}，
     * 同一视觉行横跨多列时按大间隙切分，如"单位名称 [大间隙] 法人代表"——
     * 段是防串列的原子单位）；每块/段只归属一个 cell。绑定结果直接写入
     * cell（value 按 y 顶→底以 \n 连接，source=PDF_TEXT、confidence=1.0）。
     * <p>
     * <b>块级禁用中心点兜底</b>：跨行大块覆盖率不足时若按中心整块归属，
     * 会把相邻字段一并吞入（如"建设地点"串列），故块级仅认覆盖率主判定，
     * 不达标即降级段级拆分；中心点辅助仅保留在段级（兜住文字贴边/轻微出格）。
     * <p>
     * <b>块级防串列守卫</b>（{@link #bindsUniformly}）：单行块横跨
     * "单位名称+负责人"两列时块级覆盖率仍可 ≈0.65 达标，若整块吞入会把
     * 负责人并入名称格——故块级整块绑定要求块内全部段经段级同款路由
     * 归属同一单元格，否则降级段级拆分。
     *
     * @return 是否存在至少一个值单元格（整网格价值判定）
     */
    private boolean bindValues(TableGrid grid, List<TextBlock> textBlocks) {
        List<TableCell> cells = grid.getCells();
        // 以 cells 列表下标为键（身份安全，规避 @Data equals 作键的隐患）
        Map<Integer, List<TextPiece>> bindings = new HashMap<>();
        for (TextBlock block : textBlocks == null ? List.<TextBlock>of() : textBlocks) {
            int blockIdx = bestCellIndex(cells, block.getBbox(), false);
            if (blockIdx >= 0 && bindsUniformly(cells, block, blockIdx)) {
                bindings.computeIfAbsent(blockIdx, key -> new ArrayList<>())
                        .add(new TextPiece(block.getText(), block.getBbox()));
                continue;
            }
            for (TextLine line : block.getLines()) {
                for (TextPiece piece : piecesOf(line)) {
                    int pieceIdx = bestCellIndex(cells, piece.bbox(), true);
                    if (pieceIdx >= 0) {
                        bindings.computeIfAbsent(pieceIdx, key -> new ArrayList<>())
                                .add(piece);
                    }
                }
            }
        }
        for (Map.Entry<Integer, List<TextPiece>> entry : bindings.entrySet()) {
            TableCell cell = cells.get(entry.getKey());
            cell.setValue(joinTopDown(entry.getValue()));
            cell.setSource(ElementSource.PDF_TEXT);
            cell.setConfidence(1.0f);
        }
        return !bindings.isEmpty();
    }

    /**
     * 块级整块绑定防串列守卫：仅当块内全部绑定单元（行/段）经段级同款路由
     * （{@link #bestCellIndex}，含中心点辅助）都归属块所在单元格时才允许
     * 整块绑定——此时整块绑定与段级拆分产出完全一致，仅保留块文字的
     * 行内空格排版；任一单元归属异格或无处可绑即降级段级拆分。
     */
    private boolean bindsUniformly(List<TableCell> cells, TextBlock block, int blockIdx) {
        for (TextLine line : block.getLines()) {
            for (TextPiece piece : piecesOf(line)) {
                if (bestCellIndex(cells, piece.bbox(), true) != blockIdx) {
                    return false;
                }
            }
        }
        return true;
    }

    // ---------- 输出过滤 ----------

    /**
     * 最优归属单元格下标（未绑定返回 -1）：
     * 主判定 = intersectionArea(text, cell)/textArea ≥ value-coverage-ratio，
     * 取覆盖率最大者；<b>平手取面积更小者（最具体单元格优先）</b>——规避
     * 骨架合并产生的"周框型"大 bbox（其 bbox 覆盖全表但并非真实数据格）；
     * 仍平手取中心所在者。辅助判定 = 覆盖率不足（但 ≥ center-fallback-min-coverage）
     * 且中心点落在覆盖率最高 cell 内（仅兜文字贴边/轻微出格，跨单元格合并行
     * 不得整行吞入），仅当 {@code allowCenterFallback} 为 true 时生效
     * （块级禁用，行级启用）。
     */
    private int bestCellIndex(List<TableCell> cells, BoundingBox box, boolean allowCenterFallback) {
        if (box == null || box.getWidth() * box.getHeight() <= 0) {
            return -1;
        }
        float centerX = box.getX() + box.getWidth() / 2f;
        float centerY = box.getY() + box.getHeight() / 2f;
        double boxArea = (double) box.getWidth() * box.getHeight();
        int best = -1;
        double bestCoverage = 0;
        double bestCellArea = Double.MAX_VALUE;
        boolean bestHasCenter = false;
        for (int i = 0; i < cells.size(); i++) {
            BoundingBox cellBox = cells.get(i).getBoundingBox();
            BoundingBox inter = box.intersection(cellBox);
            double coverage = inter == null ? 0
                    : (double) inter.getWidth() * inter.getHeight() / boxArea;
            double cellArea = (double) cellBox.getWidth() * cellBox.getHeight();
            boolean hasCenter = cellBox.contains(centerX, centerY);
            boolean better = best < 0 || coverage > bestCoverage;
            if (!better && coverage == bestCoverage) {
                better = cellArea < bestCellArea
                        || (cellArea == bestCellArea && hasCenter && !bestHasCenter);
            }
            if (better) {
                best = i;
                bestCoverage = coverage;
                bestCellArea = cellArea;
                bestHasCenter = hasCenter;
            }
        }
        if (bestCoverage >= valueCoverageRatio) {
            return best;
        }
        // 中心点辅助仅兜"轻微出格"（覆盖率 ≥ center-fallback-min-coverage）：
        // 跨单元格合并行不得经中心点整行吞入，防止相邻字段串列
        return allowCenterFallback && bestHasCenter
                && bestCoverage >= centerFallbackMinCoverage ? best : -1;
    }

    // ---------- 临时文件与内部模型 ----------

    /**
     * 表头链路：HeaderCandidateDetector 筛选候选（含<b>文字层门</b>——仅缺少
     * 原生文字层的格进 OCR，§十"只对缺少文字层的视觉区域补充"）→ 仅候选
     * <b>裁剪回验</b>+ OCR（超差/失败/低置信度均优雅降级 header=null，不中断）
     * → 值单元格绑定行重叠、列在其左侧的最近表头（FUSION）。
     */
    private void bindHeaders(TableGrid grid, TableRecognitionInput input) {
        if (!headerOcrEnabled) {
            return;
        }
        List<HeaderCandidateDetector.HeaderCandidate> candidates = headerCandidateDetector.detect(
                grid.getCells(), input.getRenderedPage(), input.getDpi(),
                input.getPageHeight(), input.getRegions(), input.getTextBlocks());
        List<TableCell> headerCells = new ArrayList<>(candidates.size());
        for (HeaderCandidateDetector.HeaderCandidate candidate : candidates) {
            OcrHeader header = ocrHeaderRegion(candidate.cell(), input);
            if (header == null) {
                continue;
            }
            TableCell cell = candidate.cell();
            cell.setHeader(header.text());
            cell.setSource(ElementSource.OCR);
            cell.setConfidence(header.confidence());
            headerCells.add(cell);
            log.info("表头 OCR 完成 cell=({},{}) header={} confidence={} reason={}",
                    cell.getRowIndex(), cell.getColumnIndex(), header.text(),
                    header.confidence(), candidate.reason());
        }
        for (TableCell valueCell : grid.getCells()) {
            if (valueCell.getValue() == null) {
                continue;
            }
            TableCell header = nearestHeader(valueCell, headerCells);
            if (header != null) {
                valueCell.setHeader(header.getHeader());
                valueCell.setSource(ElementSource.FUSION);
                valueCell.setConfidence(0.5f + 0.5f * header.getConfidence());
            }
        }
    }

    /**
     * 表头区域 OCR：候选单元格裁剪 → <b>裁剪回验</b>（不超出候选格，违反即
     * 降级）→ 临时 PNG 即用即删 → 识别 → 词按 lineNo 分行、行内空格连接、
     * 行间 \n 连接；confidence = 全词均值/100。
     * FAILED/EMPTY/异常 → null（优雅降级，不中断）。
     */
    private OcrHeader ocrHeaderRegion(TableCell cell, TableRecognitionInput input) {
        int[] crop = coordinateMatcher.pixelCropBounds(cell.getBoundingBox(), input.getDpi(),
                input.getPageHeight(), input.getRenderedPage().getWidth(),
                input.getRenderedPage().getHeight());
        if (!isCropWithinCell(crop, cell.getBoundingBox(), input.getDpi(), input.getPageHeight())) {
            log.warn("表头裁剪超出候选单元格，放弃 OCR 降级 cell=({},{}) crop={},{},{},{}",
                    cell.getRowIndex(), cell.getColumnIndex(),
                    crop[0], crop[1], crop[2], crop[3]);
            return null;
        }
        File cropFile = null;
        try {
            cropFile = writeCropTempPng(input.getRenderedPage(), crop);
            OcrResult result = ocrParser.recognize(OcrRequest.builder()
                    .imageFile(cropFile)
                    .pageNumber(input.getPageNumber())
                    .imageWidth(crop[2])
                    .imageHeight(crop[3])
                    .dpi(Math.round(input.getDpi()))
                    .build());
            if (!result.isSuccess()) {
                log.warn("表头 OCR 失败降级 cell=({},{}) status={}", cell.getRowIndex(),
                        cell.getColumnIndex(), result.getStatus());
                return null;
            }
            String text = result.linesOf(input.getPageNumber()).stream()
                    .map(line -> line.stream().map(OcrWord::getText).collect(Collectors.joining(" ")))
                    .collect(Collectors.joining("\n"));
            if (text.isBlank()) {
                return null;
            }
            double confidence = result.getPages().stream()
                    .flatMap(page -> page.getWords().stream())
                    .mapToDouble(OcrWord::getConfidence)
                    .average()
                    .orElse(0d) / 100d;
            if (confidence < headerMinConfidence) {
                log.warn("表头 OCR 置信度不足降级 cell=({},{}) confidence={}",
                        cell.getRowIndex(), cell.getColumnIndex(), confidence);
                return null;
            }
            return new OcrHeader(text, (float) confidence);
        } catch (IOException e) {
            log.warn("表头裁剪失败降级 cell=({},{})", cell.getRowIndex(), cell.getColumnIndex(), e);
            return null;
        } finally {
            deleteQuietly(cropFile);
        }
    }

    /**
     * 裁剪回验：crop（像素）经 {@link CoordinateMatcher#toPdfBox} 往返回换
     * PDF 空间后，必须落在候选 Cell bbox 外扩容差内——<b>裁剪 bbox 不得超出
     * 候选单元格</b>（§十"OCR 只对 Header Region 补充"的阶段 8 收紧）。
     * <p>
     * {@link CoordinateMatcher#pixelCropBounds} 采用 floor/ceil <b>外扩舍入</b>
     * （≤1px）且仅按图像边界收拢，容差取 {@code dpi/72（1px 的 pt 当量）+
     * 0.5pt 裕量}；超差说明坐标变换口径不一致或实现缺陷，按降级原则放弃该格
     * 表头 OCR（不 OCR 超格内容，header=null 优雅降级）。
     */
    private boolean isCropWithinCell(int[] crop, BoundingBox cellBbox, float dpi, float pageHeight) {
        if (crop == null || crop[2] <= 0 || crop[3] <= 0 || cellBbox == null) {
            return false;
        }
        BoundingBox cropPdf = coordinateMatcher.toPdfBox(BoundingBox.builder()
                .x(crop[0]).y(crop[1]).width(crop[2]).height(crop[3]).build(), dpi, pageHeight);
        float tolerancePt = dpi / 72f + 0.5f;
        return cellBbox.expand(tolerancePt).contains(cropPdf);
    }

    /**
     * 值绑定文字片（整块或单行）。
     */
    private record TextPiece(String text, BoundingBox bbox) {
    }

    /**
     * 表头 OCR 结果（text + 置信度 0~1）。
     */
    private record OcrHeader(String text, float confidence) {
    }
}
