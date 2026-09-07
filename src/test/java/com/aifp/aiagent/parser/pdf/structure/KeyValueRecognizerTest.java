package com.aifp.aiagent.parser.pdf.structure;

import com.aifp.aiagent.parser.pdf.ast.KeyValueNode;
import com.aifp.aiagent.parser.pdf.layout.TableCell;
import com.aifp.aiagent.parser.pdf.layout.TableGrid;
import com.aifp.aiagent.parser.pdf.page.ElementSource;
import com.aifp.aiagent.parser.pdf.text.BoundingBox;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link KeyValueRecognizer} 单元测试（阶段 9）：
 * 仅 FUSION 格（header+value 双非空）提取、纯表头格/纯值格排除、
 * 结构序确定性、来源/置信度/bbox 透传。
 *
 * @author Tang_tzb
 */
class KeyValueRecognizerTest {

    private final KeyValueRecognizer recognizer = new KeyValueRecognizer();

    @Test
    void fusionCell_extractedWithPassthrough() {
        // header+value 双非空 → 提取，字段全量透传
        BoundingBox bbox = BoundingBox.builder().x(320f).y(550f).width(180f).height(30f).build();
        TableCell cell = TableCell.builder()
                .rowIndex(0).columnIndex(1).rowSpan(1).colSpan(1)
                .boundingBox(bbox)
                .header("建设 单位").value("亳州市教育局")
                .source(ElementSource.FUSION).confidence(0.9f)
                .build();
        TableGrid grid = TableGrid.builder().pageNumber(1)
                .rowCount(1).columnCount(2)
                .rows(List.of()).cells(List.of(cell))
                .build();

        List<KeyValueNode> keyValues = recognizer.extract(grid);

        assertThat(keyValues).hasSize(1);
        KeyValueNode keyValue = keyValues.get(0);
        assertThat(keyValue.getKey()).isEqualTo("建设 单位");
        assertThat(keyValue.getValue()).isEqualTo("亳州市教育局");
        assertThat(keyValue.getKeySource()).isEqualTo(ElementSource.OCR);
        assertThat(keyValue.getValueSource()).isEqualTo(ElementSource.PDF_TEXT);
        assertThat(keyValue.getSource()).isEqualTo(ElementSource.FUSION);
        assertThat(keyValue.getConfidence()).isEqualTo(0.9f);
        assertThat(keyValue.getBbox()).isSameAs(bbox);
    }

    @Test
    void pureHeaderCell_excluded() {
        // 纯表头格（value=null）→ 不构成键值
        assertThat(recognizer.extract(grid(cell(0, 0, "单位名称", null)))).isEmpty();
    }

    @Test
    void pureValueCell_excluded() {
        // 纯值格（header=null）→ 不构成键值
        assertThat(recognizer.extract(grid(cell(0, 0, null, "XX公司")))).isEmpty();
    }

    @Test
    void cellsKeepStructureOrder_deterministic() {
        // 多键值按格网结构序（行-列）输出，天然确定
        TableGrid grid = grid(cell(0, 0, "建设单位", "教育局"), cell(1, 0, "建筑面积", "1000㎡"));

        List<KeyValueNode> keyValues = recognizer.extract(grid);

        assertThat(keyValues).hasSize(2);
        assertThat(keyValues.get(0).getKey()).isEqualTo("建设单位");
        assertThat(keyValues.get(1).getKey()).isEqualTo("建筑面积");
    }

    @Test
    void nullGridOrEmptyCells_emptyList() {
        assertThat(recognizer.extract(null)).isEmpty();
        assertThat(recognizer.extract(TableGrid.builder()
                .pageNumber(1).rowCount(2).columnCount(2)
                .rows(List.of()).cells(List.of())
                .build())).isEmpty();
    }

    // ---------- 夹具 ----------

    private TableCell cell(int rowIndex, int columnIndex, String header, String value) {
        return TableCell.builder()
                .rowIndex(rowIndex).columnIndex(columnIndex).rowSpan(1).colSpan(1)
                .boundingBox(BoundingBox.builder().x(100f).y(300f).width(100f).height(50f).build())
                .header(header).value(value)
                .source(ElementSource.PDF_TEXT).confidence(1.0f)
                .build();
    }

    private TableGrid grid(TableCell... cells) {
        return TableGrid.builder()
                .pageNumber(1)
                .rowCount(2).columnCount(2)
                .rows(List.of())
                .cells(List.of(cells))
                .build();
    }
}
