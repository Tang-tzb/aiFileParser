package com.aifp.aiagent.parser.pdf.ast;

import com.aifp.aiagent.parser.pdf.PdfInspectionResult;
import com.aifp.aiagent.parser.pdf.page.PageDocument;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 文档 AST 组装器（阶段 9）：文件名 + 检测结果 + 逐页解析产物 → {@link DocumentAst}。
 * <p>
 * documentId 为 parse 期生成 UUID（fileId 关联留待生产接线阶段）；
 * 页序保持 PDF 页序，逐页委托 {@link PageNodeAssembler}。
 *
 * @author Tang_tzb
 */
@Component
@RequiredArgsConstructor
public class DocumentAstAssembler {

    private final PageNodeAssembler pageNodeAssembler;

    /**
     * 组装文档 AST。
     *
     * @param fileName      源文件名
     * @param inspection    文档级检测结果（totalPages/documentType）
     * @param pageDocuments 逐页解析产物（顺序 = PDF 页序）
     * @return 文档 AST
     */
    public DocumentAst assemble(String fileName, PdfInspectionResult inspection,
                                List<PageDocument> pageDocuments) {
        List<PageNode> pages = new ArrayList<>();
        if (pageDocuments != null) {
            for (PageDocument pageDocument : pageDocuments) {
                pages.add(pageNodeAssembler.assemble(pageDocument));
            }
        }
        return DocumentAst.builder()
                .documentId(UUID.randomUUID().toString())
                .fileName(fileName)
                .metadata(DocumentAstMetadata.builder()
                        .totalPages(inspection != null ? inspection.getTotalPages() : 0)
                        .documentType(inspection != null ? inspection.getDocumentType() : null)
                        .build())
                .pages(List.copyOf(pages))
                .build();
    }
}
