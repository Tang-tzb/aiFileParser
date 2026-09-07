package com.aifp.aiagent.parser.pdf.clean;

import com.aifp.aiagent.parser.pdf.ast.*;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * 重复内容清洗器（阶段 10，对应《PDF解析改造方案》"重复内容"项；
 * 保守策略经用户确认 2026-09-07）。
 * <p>
 * 职责边界（全部以<b>重建新列表</b>表达，硬约束 1；仅删除元素，阅读序不变）：
 * <ul>
 *   <li><b>同页段落去重</b>：PARAGRAPH 按 {@link CharacterCleaner#normalize} 键去重，
 *       保留阅读序首个——仅同页（跨页重复正文不动，如免责声明属合法重复）；
 *       TITLE/TABLE/占位/HEADER/FOOTER 不参与（短文本误判风险 + 页眉页脚
 *       天然跨页重复，归 {@link HeaderFooterCleaner} 管辖）；</li>
 *   <li><b>空白段删除</b>：清洗后空白（无有效字符）的 PARAGRAPH/TITLE 删除；</li>
 *   <li><b>键值去重</b>：(key, value) 完全相同保留首个；<b>同键不同值保留</b>
 *       （真实样例：设计/施工/监理单位各出现 2 次、值不同，属合法数据）。</li>
 * </ul>
 *
 * @author Tang_tzb
 */
@Component
public class DuplicateCleaner {

    private final CharacterCleaner characterCleaner;

    public DuplicateCleaner(CharacterCleaner characterCleaner) {
        this.characterCleaner = characterCleaner;
    }

    /**
     * 单页去重入口：空白段删除 + 同页段落去重（重建新列表）。
     *
     * @param nodes 页内节点列表（阅读序；可 null）
     * @return 去重后新列表；null 输入返回空列表
     */
    public List<DocumentNode> dedupPage(List<DocumentNode> nodes) {
        if (nodes == null) {
            return List.of();
        }
        List<DocumentNode> result = new ArrayList<>(nodes.size());
        Set<String> seenKeys = new HashSet<>();
        for (DocumentNode node : nodes) {
            if (node == null) {
                continue;
            }
            if (isBlankTextual(node)) {
                continue;
            }
            if (node instanceof ParagraphNode && isDuplicateParagraph(node, seenKeys)) {
                continue;
            }
            result.add(node);
        }
        return result;
    }

    /**
     * 键值去重：(key, value) 完全相同保留首个；同键不同值保留（阅读序）。
     * <p>
     * KeyValueNode 为 identity equals（无值语义），故以 (key,value) 二元列表
     * 作为比较键（List equals 为值语义，null 安全）。
     *
     * @param keyValues 键值列表（可 null）
     * @return 去重后新列表；null 输入返回空列表
     */
    public List<KeyValueNode> dedupKeyValues(List<KeyValueNode> keyValues) {
        if (keyValues == null) {
            return List.of();
        }
        List<KeyValueNode> result = new ArrayList<>(keyValues.size());
        Set<List<String>> seen = new HashSet<>();
        for (KeyValueNode kv : keyValues) {
            if (kv == null) {
                continue;
            }
            if (seen.add(Arrays.asList(kv.getKey(), kv.getValue()))) {
                result.add(kv);
            }
        }
        return result;
    }

    /**
     * 空白段判定：仅针对 PARAGRAPH/TITLE（文本承载型节点），
     * 其他类型（TABLE/占位/HEADER/FOOTER）不做空白删除。
     */
    private boolean isBlankTextual(DocumentNode node) {
        if (node.getType() != DocumentNodeType.PARAGRAPH
                && node.getType() != DocumentNodeType.TITLE) {
            return false;
        }
        String text = node instanceof ParagraphNode paragraph
                ? paragraph.getText()
                : ((TitleNode) node).getText();
        return text == null || text.isBlank();
    }

    /**
     * 同页段落重复判定：normalize 键已见过 → 重复；键为空（理论不可达，
     * 空白已删）不参与分组。
     */
    private boolean isDuplicateParagraph(DocumentNode node, Set<String> seenKeys) {
        String key = characterCleaner.normalize(((ParagraphNode) node).getText());
        return key != null && !key.isEmpty() && !seenKeys.add(key);
    }
}
