package com.aifp.aiagent.parser.pdf.clean;

import com.aifp.aiagent.parser.pdf.ast.KeyValueNode;
import com.aifp.aiagent.parser.pdf.ast.TableCellNode;
import com.aifp.aiagent.parser.pdf.ast.TableNode;
import com.aifp.aiagent.parser.pdf.ast.TableRowNode;
import com.aifp.aiagent.parser.pdf.page.ElementSource;
import com.aifp.aiagent.parser.pdf.text.BoundingBox;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link TableCleaner} 单元测试（阶段 10）：
 * 单元格值多行归一、表头 CJK 空白压缩与数字纠错、keyValues 重建一致性、
 * bbox 引用透传（硬约束 2）。
 *
 * @author Tang_tzb
 */
class TableCleanerTest {

    private TableCleaner cleaner;

    @BeforeEach
    void setUp() {
        cleaner = new TableCleaner(
                new CharacterCleaner(), new LineCleaner(), new OcrErrorCleaner());
    }

    @Test
    void nullTable_passthrough() {
        assertThat(cleaner.cleanTable(null)).isNull();
        assertThat(cleaner.rebuildKeyValues(null)).isEmpty();
    }

    @Test
    void multiLineCellValue_joinedToOneLine() {
        // §十六 验收案例：建设地点 4 行 → 单一 Cell 单行
        TableCellNode cell = cell(0, 1, "建设地点",
                "南地块位于养生大道以南，\n古井大道以东；\n"
                        + "北地块位于谯城经开区古井大道以东，\n桐花路（原创业西路）以南");
        TableNode table = table(List.of(cell));

        TableNode cleaned = cleaner.cleanTable(table);

        assertThat(cleaned.getRows().get(0).getCells().get(0).getValue())
                .isEqualTo("南地块位于养生大道以南，古井大道以东；"
                        + "北地块位于谯城经开区古井大道以东，桐花路（原创业西路）以南");
    }

    @Test
    void header_cjkWhitespaceCompressed() {
        // OCR 表头内部空白压缩（阶段 8 经验：OCR 词以空格连接）
        TableCellNode cell = cell(0, 0, "建 设 单 位", "亳州市教育局");
        TableNode cleaned = cleaner.cleanTable(table(List.of(cell)));

        assertThat(cleaned.getRows().get(0).getCells().get(0).getHeader())
                .isEqualTo("建设单位");
    }

    @Test
    void header_latinBoundary_spaceKept() {
        // Latin 边界保留单空格（Unit Price ≠ UnitPrice）
        TableCellNode cell = cell(0, 0, "Unit  Price", "100");
        TableNode cleaned = cleaner.cleanTable(table(List.of(cell)));

        assertThat(cleaned.getRows().get(0).getCells().get(0).getHeader())
                .isEqualTo("Unit Price");
    }

    @Test
    void header_ocrDigitFix_applied() {
        // 表头为 OCR 源 → 数字纠错生效
        TableCellNode cell = cell(0, 0, "1O 楼平面", "值");
        TableNode cleaned = cleaner.cleanTable(table(List.of(cell)));

        assertThat(cleaned.getRows().get(0).getCells().get(0).getHeader())
                .isEqualTo("10 楼平面");
    }

    @Test
    void value_ocrFix_neverApplied() {
        // 值为 PDF_TEXT 零损耗：数字形态不纠正（Letter O 语境保持）
        TableCellNode cell = cell(0, 1, "备注", "Room 1O2A");
        TableNode cleaned = cleaner.cleanTable(table(List.of(cell)));

        assertThat(cleaned.getRows().get(0).getCells().get(0).getValue())
                .isEqualTo("Room 1O2A");
    }

    @Test
    void bbox_passthrough_sameReference() {
        // 硬约束 2：bbox 引用透传，无坐标复制/变换
        BoundingBox cellBbox = bbox(60, 300, 200, 40);
        BoundingBox rowBbox = bbox(60, 300, 400, 40);
        BoundingBox tableBbox = bbox(60, 300, 400, 100);
        TableCellNode cell = new TableCellNode(0, 0, 1, 1, "字段", "值\n值2",
                ElementSource.FUSION, 0.9f, cellBbox);
        TableNode table = new TableNode(1, 1,
                List.of(new TableRowNode(0, rowBbox, List.of(cell))),
                ElementSource.FUSION, tableBbox);

        TableNode cleaned = cleaner.cleanTable(table);

        assertThat(cleaned.getBbox()).isSameAs(tableBbox);
        assertThat(cleaned.getRows().get(0).getBbox()).isSameAs(rowBbox);
        assertThat(cleaned.getRows().get(0).getCells().get(0).getBbox()).isSameAs(cellBbox);
    }

    @Test
    void rebuildKeyValues_fusionFilterAndOrder() {
        // 仅 header+value 双全的 FUSION 格产出键值；纯表头/纯值格排除；行主序
        TableCellNode fusion = cell(0, 1, "建设单位", "亳州市教育局");
        TableCellNode headerOnly = cell(1, 0, "字段", null);
        TableCellNode valueOnly = cell(1, 1, null, "纯值");
        TableNode cleaned = cleaner.cleanTable(table(List.of(fusion, headerOnly, valueOnly)));

        List<KeyValueNode> kvs = cleaner.rebuildKeyValues(List.of(cleaned));

        assertThat(kvs).hasSize(1);
        assertThat(kvs.get(0).getKey()).isEqualTo("建设单位");
        assertThat(kvs.get(0).getValue()).isEqualTo("亳州市教育局");
        assertThat(kvs.get(0).getKeySource()).isEqualTo(ElementSource.OCR);
        assertThat(kvs.get(0).getValueSource()).isEqualTo(ElementSource.PDF_TEXT);
        assertThat(kvs.get(0).getConfidence()).isEqualTo(0.9f);
        assertThat(kvs.get(0).getBbox()).isSameAs(fusion.getBbox());
    }

    @Test
    void multiLineValue_kvValueSyncedWithCell() {
        // 重建的 KV value 与清洗后 cell value 一致（单一事实源）
        TableCellNode cell = cell(0, 1, "建设规模", "南地块位于路南，\n北地块位于路北");
        TableNode cleaned = cleaner.cleanTable(table(List.of(cell)));

        List<KeyValueNode> kvs = cleaner.rebuildKeyValues(List.of(cleaned));

        assertThat(kvs.get(0).getValue()).isEqualTo("南地块位于路南，北地块位于路北");
    }

    @Test
    void skeletonPreserved_rowSpanColSpanAndCounts() {
        // 骨架语义不变：rowCount/columnCount/rowSpan/colSpan/rowIndex/columnIndex
        TableCellNode cell = new TableCellNode(0, 1, 2, 1, "字段", "值",
                ElementSource.FUSION, 0.9f, bbox(0, 0, 10, 10));
        TableNode table = new TableNode(3, 2,
                List.of(new TableRowNode(0, null, List.of(cell))),
                ElementSource.FUSION, null);

        TableNode cleaned = cleaner.cleanTable(table);
        TableCellNode cleanedCell = cleaned.getRows().get(0).getCells().get(0);

        assertThat(cleaned.getRowCount()).isEqualTo(3);
        assertThat(cleaned.getColumnCount()).isEqualTo(2);
        assertThat(cleanedCell.getRowIndex()).isZero();
        assertThat(cleanedCell.getColumnIndex()).isEqualTo(1);
        assertThat(cleanedCell.getRowSpan()).isEqualTo(2);
        assertThat(cleanedCell.getColSpan()).isEqualTo(1);
    }

    // ---------- 夹具 ----------

    private TableCellNode cell(int row, int col, String header, String value) {
        return new TableCellNode(row, col, 1, 1, header, value,
                ElementSource.FUSION, 0.9f, bbox(row * 10, col * 10, 100, 20));
    }

    private TableNode table(List<TableCellNode> cells) {
        return new TableNode(cells.size(), cells.size(),
                List.of(new TableRowNode(0, null, cells)), ElementSource.FUSION,
                bbox(0, 0, 400, 100));
    }

    private BoundingBox bbox(float x, float y, float width, float height) {
        return BoundingBox.builder().x(x).y(y).width(width).height(height).build();
    }
}
