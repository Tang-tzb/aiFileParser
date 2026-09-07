package com.aifp.aiagent.parser.pdf.ast;

import com.aifp.aiagent.parser.pdf.PageContentType;
import com.aifp.aiagent.parser.pdf.PdfContentType;
import com.aifp.aiagent.parser.pdf.PdfInspectionResult;
import com.aifp.aiagent.parser.pdf.page.PageDocument;
import com.aifp.aiagent.parser.pdf.page.PageElement;
import com.aifp.aiagent.parser.pdf.text.BoundingBox;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link DocumentAstAssembler} 单元测试（阶段 9）：
 * 元数据透传、页序保持、documentId UUID 契约、null 兜底。
 *
 * @author Tang_tzb
 */
class DocumentAstAssemblerTest {

    private final DocumentAstAssembler assembler =
            new DocumentAstAssembler(AstTestSupport.buildPageNodeAssembler());

    @Test
    void assemble_metadataAndPageOrderPreserved() {
        // 元数据透传 + 页序 = PDF 页序 + 逐页委托组装
        PdfInspectionResult inspection = PdfInspectionResult.builder()
                .totalPages(2)
                .documentType(PdfContentType.MIXED)
                .pages(List.of())
                .build();
        PageDocument page1 = pageDocument(1, PageContentType.TEXT_ONLY,
                List.of(AstTestSupport.textElement("page one text", 12f, bbox(60, 600, 200, 20))));
        PageDocument page2 = pageDocument(2, PageContentType.IMAGE_ONLY,
                List.of(AstTestSupport.ocrElement("page two ocr", 88f, bbox(60, 500, 200, 20))));

        DocumentAst ast = assembler.assemble("sample.pdf", inspection, List.of(page1, page2));

        assertThat(ast.getFileName()).isEqualTo("sample.pdf");
        assertThat(ast.getMetadata().getTotalPages()).isEqualTo(2);
        assertThat(ast.getMetadata().getDocumentType()).isEqualTo(PdfContentType.MIXED);
        assertThat(ast.getPages()).extracting(PageNode::getPageNumber).containsExactly(1, 2);
        // 页内容委托 PageNodeAssembler 组装
        assertThat(ast.getPages().get(0).getContentType()).isEqualTo(PageContentType.TEXT_ONLY);
        assertThat(ast.getPages().get(0).getNodes()).hasSize(1);
        assertThat(ast.getPages().get(1).getContentType()).isEqualTo(PageContentType.IMAGE_ONLY);
    }

    @Test
    void documentId_validUuidAndUniqueAcrossCalls() {
        // documentId 为 parse 期 UUID：可解析且每次组装唯一
        DocumentAst first = assembler.assemble("a.pdf", inspection(1), List.of());
        DocumentAst second = assembler.assemble("b.pdf", inspection(1), List.of());

        assertThat(UUID.fromString(first.getDocumentId())).isNotNull();
        assertThat(UUID.fromString(second.getDocumentId())).isNotNull();
        assertThat(first.getDocumentId()).isNotEqualTo(second.getDocumentId());
    }

    @Test
    void nullInspection_metadataDefaults() {
        // inspection=null 防御：totalPages=0、documentType=null
        DocumentAst ast = assembler.assemble("a.pdf", null, List.of());

        assertThat(ast.getMetadata().getTotalPages()).isZero();
        assertThat(ast.getMetadata().getDocumentType()).isNull();
        assertThat(ast.getPages()).isEmpty();
    }

    @Test
    void nullPageDocuments_emptyPages() {
        // pageDocuments=null 防御 → 空 pages
        DocumentAst ast = assembler.assemble("a.pdf", inspection(0), null);

        assertThat(ast.getPages()).isEmpty();
    }

    // ---------- 夹具 ----------

    private PdfInspectionResult inspection(int totalPages) {
        return PdfInspectionResult.builder()
                .totalPages(totalPages)
                .documentType(PdfContentType.TEXT_ONLY)
                .pages(List.of())
                .build();
    }

    private PageDocument pageDocument(int pageNumber, PageContentType contentType,
                                      List<PageElement> elements) {
        return PageDocument.builder()
                .pageNumber(pageNumber)
                .contentType(contentType)
                .pageWidth(595f)
                .pageHeight(842f)
                .elements(elements)
                .parserName("TestPageParser")
                .build();
    }

    private BoundingBox bbox(float x, float y, float width, float height) {
        return BoundingBox.builder().x(x).y(y).width(width).height(height).build();
    }
}
