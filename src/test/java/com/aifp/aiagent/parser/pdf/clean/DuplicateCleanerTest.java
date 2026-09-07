package com.aifp.aiagent.parser.pdf.clean;

import com.aifp.aiagent.parser.pdf.ast.*;
import com.aifp.aiagent.parser.pdf.page.ElementSource;
import com.aifp.aiagent.parser.pdf.text.BoundingBox;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link DuplicateCleaner} 单元测试（阶段 10）：
 * 同页段落去重（保首个）、空白段删除、TITLE/页眉页脚不参与、
 * 跨页不动（无状态）、键值 (key,value) 完全重复去重 + 同键不同值保留。
 *
 * @author Tang_tzb
 */
class DuplicateCleanerTest {

    private DuplicateCleaner cleaner;

    @BeforeEach
    void setUp() {
        cleaner = new DuplicateCleaner(new CharacterCleaner());
    }

    @Test
    void nullInput_emptyResult() {
        assertThat(cleaner.dedupPage(null)).isEmpty();
        assertThat(cleaner.dedupKeyValues(null)).isEmpty();
    }

    @Test
    void samePageDuplicateParagraph_keepFirst() {
        DocumentNode first = paragraph("重复段落内容");
        DocumentNode second = paragraph("重复段落内容");
        DocumentNode body = paragraph("其他内容");

        List<DocumentNode> result = cleaner.dedupPage(List.of(first, second, body));

        assertThat(result).containsExactly(first, body);
    }

    @Test
    void whitespaceDifference_sameDedupKey() {
        // 比较键经 normalize：空白串长度/换行差异不算内容差异（连续空白折叠为单空格）
        DocumentNode first = paragraph("相同  内容");
        DocumentNode second = paragraph("相同 内容");

        List<DocumentNode> result = cleaner.dedupPage(List.of(first, second));

        assertThat(result).hasSize(1);
        assertThat(result.get(0)).isSameAs(first);
    }

    @Test
    void crossPageDedup_notPerformed_stateless() {
        // 跨页重复正文不动（合法重复，如免责声明）：dedupPage 无状态，逐页独立调用
        List<DocumentNode> page1Result = cleaner.dedupPage(List.of(paragraph("免责声明全文……")));
        List<DocumentNode> page2Result = cleaner.dedupPage(List.of(paragraph("免责声明全文……")));

        assertThat(page1Result).hasSize(1);
        assertThat(page2Result).hasSize(1);
    }

    @Test
    void blankParagraphAndTitle_removed() {
        ParagraphNode blankParagraph = new ParagraphNode("  \n ",
                ElementSource.PDF_TEXT, 1.0f, bbox(), 10f, "SimSun");
        TitleNode blankTitle = new TitleNode(" ", 16f, "SimHei", bbox());
        DocumentNode kept = paragraph("有效内容");

        List<DocumentNode> result = cleaner.dedupPage(List.of(blankParagraph, blankTitle, kept));

        assertThat(result).hasSize(1);
        assertThat(result.get(0)).isSameAs(kept);
    }

    @Test
    void titleNotDeduplicated() {
        // TITLE 不参与去重（防章节标题短文本误判）
        TitleNode title1 = title("第一章 总则");
        TitleNode title2 = title("第一章 总则");

        List<DocumentNode> result = cleaner.dedupPage(List.of(title1, title2));

        assertThat(result).hasSize(2);
        assertThat(result.get(0)).isSameAs(title1);
        assertThat(result.get(1)).isSameAs(title2);
    }

    @Test
    void headerFooterNodes_notParticipating() {
        // 页眉页脚占位节点（基类 DocumentNode）不参与段落去重：
        // 相邻同 description 的 HEADER 节点均保留
        DocumentNode header1 = baseNode(DocumentNodeType.HEADER, "第 1 页");
        DocumentNode header2 = baseNode(DocumentNodeType.HEADER, "第 1 页");

        List<DocumentNode> result = cleaner.dedupPage(List.of(header1, header2));

        assertThat(result).hasSize(2);
    }

    @Test
    void paragraphEqualToHeaderDescription_notDeduped() {
        // HEADER（重分类产物）不进入段落键集合，同文本段落保留
        DocumentNode header = baseNode(DocumentNodeType.HEADER, "某文本");
        DocumentNode paragraph = paragraph("某文本");

        List<DocumentNode> result = cleaner.dedupPage(List.of(header, paragraph));

        assertThat(result).hasSize(2);
    }

    @Test
    void keptNode_referencesAndOrderPreserved() {
        // 硬约束 1/2：保留节点原引用透传（含 bbox），阅读序不变
        DocumentNode first = paragraph("甲段");
        DocumentNode second = paragraph("乙段");

        List<DocumentNode> result = cleaner.dedupPage(List.of(first, second));

        assertThat(result).containsExactly(first, second);
        assertThat(result.get(0).getBbox()).isSameAs(first.getBbox());
    }

    @Test
    void keyValues_exactDuplicate_keepFirst() {
        KeyValueNode first = kv("建设单位", "亳州市教育局", bbox());
        KeyValueNode second = kv("建设单位", "亳州市教育局", bbox());

        List<KeyValueNode> result = cleaner.dedupKeyValues(List.of(first, second));

        assertThat(result).hasSize(1);
        assertThat(result.get(0)).isSameAs(first);
    }

    @Test
    void keyValues_sameKeyDifferentValue_keepBoth() {
        // 真实样例对齐：设计/施工/监理单位键各出现 2 次、值不同 → 全部保留
        KeyValueNode design1 = kv("设计单位", "甲设计院", bbox());
        KeyValueNode design2 = kv("设计单位", "乙设计院", bbox());
        KeyValueNode supervise = kv("监理单位", "丙监理公司", bbox());

        List<KeyValueNode> result = cleaner.dedupKeyValues(List.of(design1, design2, supervise));

        assertThat(result).containsExactly(design1, design2, supervise);
    }

    @Test
    void keyValues_sameValueDifferentKey_keepBoth() {
        List<KeyValueNode> result = cleaner.dedupKeyValues(
                List.of(kv("建设单位", "同值", bbox()), kv("施工类别", "同值", bbox())));

        assertThat(result).hasSize(2);
    }

    @Test
    void keyValues_nullEntriesSkipped() {
        KeyValueNode kv = kv("键", "值", bbox());

        List<KeyValueNode> result = cleaner.dedupKeyValues(
                java.util.Arrays.asList(null, kv, null));

        assertThat(result).containsExactly(kv);
    }

    // ---------- 夹具 ----------

    private ParagraphNode paragraph(String text) {
        return new ParagraphNode(text, ElementSource.PDF_TEXT, 1.0f, bbox(), 10f, "SimSun");
    }

    private TitleNode title(String text) {
        return new TitleNode(text, 16f, "SimHei", bbox());
    }

    /**
     * 页眉页脚重分类产物形态：基类 DocumentNode + description 承载文本
     */
    private DocumentNode baseNode(DocumentNodeType type, String description) {
        return new DocumentNode(type, ElementSource.PDF_TEXT, 1.0f, bbox(), description);
    }

    private KeyValueNode kv(String key, String value, BoundingBox bbox) {
        return new KeyValueNode(key, value, ElementSource.OCR, ElementSource.PDF_TEXT, 0.9f, bbox);
    }

    private BoundingBox bbox() {
        return BoundingBox.builder().x(100).y(400).width(300).height(30).build();
    }
}
