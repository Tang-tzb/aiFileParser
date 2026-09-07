package com.aifp.aiagent.parser.pdf.clean;

import com.aifp.aiagent.parser.pdf.ast.*;
import com.aifp.aiagent.parser.pdf.page.ElementSource;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 文档清洗门面（阶段 10，对应《PDF解析改造方案》§十五）：
 * 6 步流水线编排，输入输出均为 DocumentAst。
 * <p>
 * 流水线（D5，顺序依据见计划）：
 * <ol>
 *   <li>{@link CharacterCleaner}：全部文本字段安全空白规范（段落/标题/单元格
 *       header+value；键值经步骤 3 重建自动携带）；</li>
 *   <li>{@link LineCleaner}：段落/标题段模式换行修复；</li>
 *   <li>{@link TableCleaner}：单元格值 Cell 模式归一 + 表头规范化/数字纠错，
 *       并<b>立即从清洗后 cells 重建 keyValues</b>（D4 单一事实源，杜绝漂移）；</li>
 *   <li>{@link OcrErrorCleaner}：source=OCR 段落兜底纠错（先接全断行数字再纠错，
 *       {@code 17O\n649.08 → 170649.08}）；</li>
 *   <li>{@link HeaderFooterCleaner}：跨页重复段重分类 HEADER/FOOTER；</li>
 *   <li>{@link DuplicateCleaner}：同页段落去重 + 空白段删除 + 键值完全重复去重。</li>
 * </ol>
 * <p>
 * <b>硬约束 1（重建新 AST）</b>：输入 AST 绝不修改——所有变更以新建节点/列表/
 * PageNode/DocumentAst 表达，仅经 public 构造器/builder 组装。
 * <b>硬约束 2（PDF_USER_SPACE）</b>：全程零坐标变换，重建节点透传原 bbox 引用。
 * 独立交付（D1）：生产接线留阶段 11/12。
 *
 * @author Tang_tzb
 */
@Component
@RequiredArgsConstructor
public class DocumentCleaner {

    private final CharacterCleaner characterCleaner;
    private final LineCleaner lineCleaner;
    private final OcrErrorCleaner ocrErrorCleaner;
    private final TableCleaner tableCleaner;
    private final HeaderFooterCleaner headerFooterCleaner;
    private final DuplicateCleaner duplicateCleaner;

    /**
     * 清洗入口：null → null；输出为全新 DocumentAst，输入内容零改动。
     *
     * @param ast 待清洗 AST（可 null）
     * @return 清洗后新 AST；null 输入原样返回
     */
    public DocumentAst clean(DocumentAst ast) {
        if (ast == null) {
            return null;
        }
        // 步骤 1~4：文本字段清洗（字符 → 段落换行 → 表格 + KV 重建 → OCR 兜底）
        List<PageNode> pages = cleanText(ast.getPages());
        // 步骤 5：页眉页脚重分类；步骤 6：去重
        pages = duplicateCleanedPages(headerFooterCleaner.reclassify(pages));
        return rebuildAst(ast, pages);
    }

    /**
     * 步骤 1~4：逐页逐节点文本清洗（节点类型分派，页内顺序不变）。
     */
    private List<PageNode> cleanText(List<PageNode> pages) {
        if (pages == null) {
            return List.of();
        }
        List<PageNode> result = new ArrayList<>(pages.size());
        for (PageNode page : pages) {
            result.add(cleanPageText(page));
        }
        return result;
    }

    /**
     * 单页文本清洗：段落/标题/表格分派；keyValues 由清洗后 cells 重建（D4）。
     */
    private PageNode cleanPageText(PageNode page) {
        if (page == null) {
            return null;
        }
        List<DocumentNode> nodes = new ArrayList<>(page.getNodes().size());
        List<TableNode> cleanedTables = new ArrayList<>();
        for (DocumentNode node : page.getNodes()) {
            nodes.add(cleanNode(node, cleanedTables));
        }
        List<KeyValueNode> keyValues = tableCleaner.rebuildKeyValues(cleanedTables);
        return PageNode.builder()
                .pageNumber(page.getPageNumber())
                .contentType(page.getContentType())
                .pageWidth(page.getPageWidth())
                .pageHeight(page.getPageHeight())
                .nodes(nodes)
                .keyValues(keyValues)
                .build();
    }

    /**
     * 节点分派：段落（步骤 1/2/4）、标题（步骤 1/2）、表格（步骤 3）；
     * 占位/HEADER/FOOTER 等无文本字段节点原引用透传。
     */
    private DocumentNode cleanNode(DocumentNode node, List<TableNode> cleanedTables) {
        if (node instanceof ParagraphNode paragraph) {
            return cleanParagraph(paragraph);
        }
        if (node instanceof TitleNode title) {
            return new TitleNode(lineCleaner.joinParagraph(characterCleaner.clean(title.getText())),
                    title.getFontSize(), title.getFontName(), title.getBbox());
        }
        if (node instanceof TableNode table) {
            TableNode cleaned = tableCleaner.cleanTable(table);
            cleanedTables.add(cleaned);
            return cleaned;
        }
        return node;
    }

    /**
     * 段落清洗链：字符规范 → 换行修复 → OCR 源兜底纠错（先接全断行数字再纠错）。
     */
    private ParagraphNode cleanParagraph(ParagraphNode paragraph) {
        String text = characterCleaner.clean(paragraph.getText());
        text = lineCleaner.joinParagraph(text);
        if (paragraph.getSource() == ElementSource.OCR) {
            text = ocrErrorCleaner.fix(text);
        }
        return new ParagraphNode(text, paragraph.getSource(), paragraph.getConfidence(),
                paragraph.getBbox(), paragraph.getFontSize(), paragraph.getFontName());
    }

    /**
     * 步骤 6：逐页去重（dedupPage 无状态，跨页重复正文不动——D2）。
     */
    private List<PageNode> duplicateCleanedPages(List<PageNode> pages) {
        List<PageNode> result = new ArrayList<>(pages.size());
        for (PageNode page : pages) {
            result.add(rebuildPage(page, duplicateCleaner.dedupPage(page.getNodes())));
        }
        return result;
    }

    /**
     * 经 builder 组装新 PageNode（页元数据保留；keyValues 完全重复去重——步骤 6）。
     */
    private PageNode rebuildPage(PageNode page, List<DocumentNode> nodes) {
        return PageNode.builder()
                .pageNumber(page.getPageNumber())
                .contentType(page.getContentType())
                .pageWidth(page.getPageWidth())
                .pageHeight(page.getPageHeight())
                .nodes(nodes)
                .keyValues(duplicateCleaner.dedupKeyValues(page.getKeyValues()))
                .build();
    }

    /**
     * 组装新 DocumentAst：documentId/fileName/metadata 原样保留。
     */
    private DocumentAst rebuildAst(DocumentAst ast, List<PageNode> pages) {
        return DocumentAst.builder()
                .documentId(ast.getDocumentId())
                .fileName(ast.getFileName())
                .metadata(ast.getMetadata())
                .pages(pages)
                .build();
    }
}
