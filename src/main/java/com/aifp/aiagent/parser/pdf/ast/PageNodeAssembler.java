package com.aifp.aiagent.parser.pdf.ast;

import com.aifp.aiagent.parser.pdf.layout.TableGrid;
import com.aifp.aiagent.parser.pdf.page.ElementSource;
import com.aifp.aiagent.parser.pdf.page.PageDocument;
import com.aifp.aiagent.parser.pdf.page.PageElement;
import com.aifp.aiagent.parser.pdf.page.PageElementType;
import com.aifp.aiagent.parser.pdf.region.RegionType;
import com.aifp.aiagent.parser.pdf.structure.KeyValueRecognizer;
import com.aifp.aiagent.parser.pdf.structure.TitleRecognizer;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 页节点组装器（阶段 9）：{@link PageDocument} → {@link PageNode}。
 * <p>
 * 元素分派：TEXT+PDF_TEXT → 标题/段落（字号中位数启发式）、TEXT+OCR → 段落
 * （confidence/100 归一 §十四）、IMAGE_REGION/PENDING_OCR → 占位节点
 * （regionType 映射 STAMP/SIGNATURE/IMAGE）；表格 → TableNode 进 nodes、
 * FUSION 格 → KeyValueNode 进 keyValues（约束 2，不进 nodes）。
 * nodes 最终按 bbox 确定性阅读序排序（约束 1）。
 *
 * @author Tang_tzb
 */
@Component
@RequiredArgsConstructor
public class PageNodeAssembler {

    private final TableNodeAssembler tableNodeAssembler;
    private final KeyValueRecognizer keyValueRecognizer;
    private final TitleRecognizer titleRecognizer;

    /**
     * 组装页节点。
     *
     * @param pageDocument 页面解析产物（阶段 2~8 契约）
     * @return 页节点
     */
    public PageNode assemble(PageDocument pageDocument) {
        List<PageElement> elements =
                pageDocument.getElements() == null ? List.of() : pageDocument.getElements();
        float medianFontSize = medianFontSizeOf(elements);

        List<DocumentNode> nodes = new ArrayList<>();
        for (PageElement element : elements) {
            DocumentNode node = toNode(element, medianFontSize);
            if (node != null) {
                nodes.add(node);
            }
        }

        List<KeyValueNode> keyValues = new ArrayList<>();
        for (TableGrid grid : tablesOf(pageDocument)) {
            TableNode tableNode = tableNodeAssembler.assemble(grid);
            if (tableNode != null) {
                nodes.add(tableNode);
            }
            keyValues.addAll(keyValueRecognizer.extract(grid));
        }

        sortByReadingOrder(nodes);
        return PageNode.builder()
                .pageNumber(pageDocument.getPageNumber())
                .contentType(pageDocument.getContentType())
                .pageWidth(pageDocument.getPageWidth())
                .pageHeight(pageDocument.getPageHeight())
                .nodes(List.copyOf(nodes))
                .keyValues(List.copyOf(keyValues))
                .build();
    }

    /**
     * 单元素 → 节点（未知类型返回 null 由调用方过滤）。
     */
    private DocumentNode toNode(PageElement element, float medianFontSize) {
        if (element == null || element.getType() == null) {
            return null;
        }
        if (element.getType() == PageElementType.TEXT) {
            return textToNode(element, medianFontSize);
        }
        // IMAGE_REGION / PENDING_OCR：视觉区域占位（STAMP/SIGNATURE/IMAGE）
        return new DocumentNode(mapRegionNodeType(element.getRegionType()),
                ElementSource.IMAGE, null, element.getBbox(), element.getDescription());
    }

    /**
     * 文字元素 → 标题/段落节点。
     */
    private DocumentNode textToNode(PageElement element, float medianFontSize) {
        if (element.getSource() == ElementSource.PDF_TEXT
                && titleRecognizer.isTitle(element.getText(), element.getFontSize(),
                medianFontSize)) {
            return new TitleNode(element.getText(), element.getFontSize(),
                    element.getFontName(), element.getBbox());
        }
        return new ParagraphNode(element.getText(), element.getSource(),
                normalizeConfidence(element), element.getBbox(),
                element.getFontSize(), element.getFontName());
    }

    /**
     * 视觉区域类型 → 占位节点类型：STAMP→STAMP、SIGNATURE→SIGNATURE、其余→IMAGE
     * （表格结构已由 TableNode 承载，未恢复的表格区域如实标记为视觉占位）。
     */
    private DocumentNodeType mapRegionNodeType(RegionType regionType) {
        if (regionType == RegionType.STAMP) {
            return DocumentNodeType.STAMP;
        }
        if (regionType == RegionType.SIGNATURE) {
            return DocumentNodeType.SIGNATURE;
        }
        return DocumentNodeType.IMAGE;
    }

    /**
     * 置信度语义（§十四）：PDF_TEXT 原生文字零损耗恒 1.0；
     * OCR 为识别置信度归一 0~1（PageElement 口径 0~100）。
     */
    private Float normalizeConfidence(PageElement element) {
        if (element.getSource() == ElementSource.PDF_TEXT) {
            return 1.0f;
        }
        Float confidence = element.getConfidence();
        if (confidence == null) {
            return null;
        }
        return confidence > 1.0f ? confidence / 100f : confidence;
    }

    /**
     * 页内 PDF_TEXT 元素的主字号中位数（无数据返回 0，标题判定兜底拒绝）。
     */
    private float medianFontSizeOf(List<PageElement> elements) {
        List<Float> sizes = new ArrayList<>();
        for (PageElement element : elements) {
            if (element.getType() == PageElementType.TEXT
                    && element.getSource() == ElementSource.PDF_TEXT
                    && element.getFontSize() != null) {
                sizes.add(element.getFontSize());
            }
        }
        if (sizes.isEmpty()) {
            return 0f;
        }
        sizes.sort(Float::compare);
        return sizes.get(sizes.size() / 2);
    }

    private List<TableGrid> tablesOf(PageDocument pageDocument) {
        return pageDocument.getTables() == null ? List.of() : pageDocument.getTables();
    }

    /**
     * 确定性阅读序（用户约束 1）：bbox 顶部优先（top=y+height 降序）→
     * 同顶按 x 升序 → 无 bbox 殿后；{@code List.sort} 为稳定排序，
     * 同位节点维持提取序，跨运行结果确定。
     */
    private void sortByReadingOrder(List<DocumentNode> nodes) {
        nodes.sort(Comparator
                .comparing((DocumentNode node) -> node.getBbox() == null)
                .thenComparing(node -> topOf(node.getBbox()), Comparator.reverseOrder())
                .thenComparing(node -> node.getBbox() == null ? 0f : node.getBbox().getX()));
    }

    private float topOf(com.aifp.aiagent.parser.pdf.text.BoundingBox bbox) {
        return bbox == null ? 0f : bbox.getY() + bbox.getHeight();
    }
}
