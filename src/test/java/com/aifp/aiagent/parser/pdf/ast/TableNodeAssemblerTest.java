package com.aifp.aiagent.parser.pdf.ast;

import com.aifp.aiagent.parser.pdf.layout.TableCell;
import com.aifp.aiagent.parser.pdf.layout.TableGrid;
import com.aifp.aiagent.parser.pdf.page.ElementSource;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link TableNodeAssembler} 单元测试（阶段 9）：
 * TableGrid → TableNode 全量透传（不清洗不重排）、表级来源聚合
 * （FUSION &gt; OCR &gt; PDF_TEXT）、null 兜底。
 *
 * @author Tang_tzb
 */
class TableNodeAssemblerTest {

    private final TableNodeAssembler assembler = new TableNodeAssembler();

    @Test
    void nullGrid_returnsNull() {
        // null 格网 → 返回 null 由调用方过滤
        assertThat(assembler.assemble(null)).isNull();
    }

    @Test
    void gridAndCellFields_passthrough() {
        // 行/列/span/header/value/source/confidence/bbox 全量透传
        TableCell headerCell = AstTestSupport.cell(0, 0, 1, 1,
                "建设单位", null, ElementSource.OCR, 0.8f);
        TableCell valueCell = AstTestSupport.cell(0, 1, 2, 1,
                null, "教育局", ElementSource.PDF_TEXT, 1.0f);
        TableGrid grid = AstTestSupport.grid(3, 2,
                List.of(AstTestSupport.row(0, List.of(headerCell, valueCell))),
                List.of(headerCell, valueCell));

        TableNode node = assembler.assemble(grid);

        assertThat(node.getType()).isEqualTo(DocumentNodeType.TABLE);
        assertThat(node.getRowCount()).isEqualTo(3);
        assertThat(node.getColumnCount()).isEqualTo(2);
        assertThat(node.getBbox()).isSameAs(grid.getBbox());
        // 结构性行节点：source/confidence 为 null
        TableRowNode rowNode = node.getRows().get(0);
        assertThat(rowNode.getType()).isEqualTo(DocumentNodeType.TABLE_ROW);
        assertThat(rowNode.getIndex()).isZero();
        assertThat(rowNode.getSource()).isNull();
        assertThat(rowNode.getConfidence()).isNull();
        // 表头格透传
        TableCellNode headerNode = rowNode.getCells().get(0);
        assertThat(headerNode.getType()).isEqualTo(DocumentNodeType.TABLE_CELL);
        assertThat(headerNode.getRowIndex()).isZero();
        assertThat(headerNode.getColumnIndex()).isZero();
        assertThat(headerNode.getHeader()).isEqualTo("建设单位");
        assertThat(headerNode.getValue()).isNull();
        assertThat(headerNode.getSource()).isEqualTo(ElementSource.OCR);
        assertThat(headerNode.getConfidence()).isEqualTo(0.8f);
        // 值格透传（含跨行）
        TableCellNode valueNode = rowNode.getCells().get(1);
        assertThat(valueNode.getColumnIndex()).isEqualTo(1);
        assertThat(valueNode.getRowSpan()).isEqualTo(2);
        assertThat(valueNode.getValue()).isEqualTo("教育局");
    }

    @Test
    void sourceAggregation_anyFusion_wins() {
        // 任一 FUSION 格 → 表级 FUSION（最高优先）
        TableCell fusionCell = AstTestSupport.cell(0, 0, 1, 1,
                "建设单位", "教育局", ElementSource.FUSION, 0.9f);
        TableCell ocrCell = AstTestSupport.cell(0, 1, 1, 1,
                "字段", null, ElementSource.OCR, 0.8f);

        TableNode node = assembler.assemble(AstTestSupport.grid(1, 2,
                List.of(), List.of(fusionCell, ocrCell)));

        assertThat(node.getSource()).isEqualTo(ElementSource.FUSION);
    }

    @Test
    void sourceAggregation_ocrWithoutFusion_ocr() {
        // 无 FUSION 但有 OCR 格 → 表级 OCR
        TableCell pdfTextCell = AstTestSupport.cell(0, 0, 1, 1,
                null, "纯文字", ElementSource.PDF_TEXT, 1.0f);
        TableCell ocrCell = AstTestSupport.cell(0, 1, 1, 1,
                "表头", null, ElementSource.OCR, 0.7f);

        TableNode node = assembler.assemble(AstTestSupport.grid(1, 2,
                List.of(), List.of(pdfTextCell, ocrCell)));

        assertThat(node.getSource()).isEqualTo(ElementSource.OCR);
    }

    @Test
    void sourceAggregation_pdfTextOnlyOrDefault_pdfText() {
        // 纯 PDF_TEXT 格 → PDF_TEXT；空单元格列表 → 默认 PDF_TEXT
        TableCell pdfTextCell = AstTestSupport.cell(0, 0, 1, 1,
                null, "纯文字", ElementSource.PDF_TEXT, 1.0f);
        TableNode fromCells = assembler.assemble(AstTestSupport.grid(1, 1,
                List.of(), List.of(pdfTextCell)));
        TableNode fromEmpty = assembler.assemble(AstTestSupport.grid(1, 1,
                List.of(), List.of()));

        assertThat(fromCells.getSource()).isEqualTo(ElementSource.PDF_TEXT);
        assertThat(fromEmpty.getSource()).isEqualTo(ElementSource.PDF_TEXT);
    }
}
