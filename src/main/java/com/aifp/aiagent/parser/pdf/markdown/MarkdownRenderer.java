package com.aifp.aiagent.parser.pdf.markdown;

import com.aifp.aiagent.parser.pdf.ast.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Markdown 渲染器（阶段 11，对应《PDF解析改造方案》阶段 11）：
 * 将 {@link DocumentAst} <b>纯投影</b>为 Markdown 字符串。
 * <p>
 * <b>硬约束 A（用户确认 2026-09-07）</b>：本类只能读取 DocumentAst
 * （仅依赖 {@code ast} 包模型 + JDK/Spring 注解），禁止任何结构再识别——
 * 不依赖解析器/OCR/表格识别/清洗器类型，不基于文本模式推断标题/表格/键值。
 * <p>
 * <b>硬约束 B（用户确认 2026-09-07）</b>：Table 是结构数据、KV 是语义视图；
 * {@code key-values-enabled} 只是 Markdown 工件的输出策略——仅影响渲染产物、
 * 不改变 DocumentAst、不得让阶段 12 产生重复语义 Chunk。落地机制：
 * KV 清单块以 {@code <!-- key-values -->} / {@code <!-- /key-values -->}
 * 注释标记包裹，任何"基于 Markdown 文本切片"的下游路径可机械排除语义视图块；
 * 阶段 12 Chunker 只消费 DocumentAst，表格块与 KV 块按 §21 互斥成块，与本开关无关。
 * <p>
 * 渲染规则：`# 文件名` 头部；页间 `---` 分隔；TITLE→`##`；PARAGRAPH→段落原文；
 * TABLE→GFM 表格（首行表头 + 分隔行，rowSpan/colSpan 展开为覆盖空格，
 * 单元格 `{@code |}`→`\|`、`\n`→`&lt;br&gt;`，rows 为空整表跳过）；
 * HEADER/FOOTER 跳过（阶段 10 重分类语义：非正文）；
 * IMAGE/STAMP/SIGNATURE/SECTION 等 description 独立成行。块间以空行分隔。
 *
 * @author Tang_tzb
 */
@Component
public class MarkdownRenderer {

    /**
     * KV 语义视图块起始标记（供下游 Markdown 切片路径机械排除）
     */
    private static final String KV_BEGIN_MARKER = "<!-- key-values -->";

    /**
     * KV 语义视图块结束标记
     */
    private static final String KV_END_MARKER = "<!-- /key-values -->";

    /**
     * 键值清单渲染开关（仅 Markdown 工件输出策略，见硬约束 B）
     */
    @Value("${document.parser.pdf.markdown.key-values-enabled:true}")
    private boolean keyValuesEnabled = true;

    /**
     * 渲染入口：null → null；块（头部/节点/键值块/页分隔）间以空行连接，
     * 末尾单换行。
     *
     * @param ast 文档 AST（可 null）
     * @return Markdown 文本；null 输入返回 null；无内容返回空串
     */
    public String render(DocumentAst ast) {
        if (ast == null) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        appendBlock(sb, documentHeader(ast.getFileName()));
        appendPages(sb, ast.getPages());
        return sb.length() == 0 ? "" : sb.append('\n').toString();
    }

    /**
     * 文档头部块：`# 文件名`（文件名空白则无头部块）。
     */
    private String documentHeader(String fileName) {
        if (fileName == null || fileName.isBlank()) {
            return null;
        }
        return "# " + fileName.strip();
    }

    /**
     * 逐页渲染：页内节点块 + 可选 KV 块；页与页之间插入 `---` 分隔块。
     */
    private void appendPages(StringBuilder sb, List<PageNode> pages) {
        if (pages == null) {
            return;
        }
        for (int i = 0; i < pages.size(); i++) {
            PageNode page = pages.get(i);
            if (page == null) {
                continue;
            }
            appendPage(sb, page);
            if (i < pages.size() - 1) {
                appendBlock(sb, "---");
            }
        }
    }

    /**
     * 单页渲染：nodes 阅读序逐节点 → 可选 KV 语义视图块（硬约束 B 标记包裹）。
     */
    private void appendPage(StringBuilder sb, PageNode page) {
        if (page.getNodes() != null) {
            for (DocumentNode node : page.getNodes()) {
                appendBlock(sb, renderNode(node));
            }
        }
        if (keyValuesEnabled && page.getKeyValues() != null) {
            appendBlock(sb, renderKeyValues(page.getKeyValues()));
        }
    }

    /**
     * 节点分派（按 AST 类型，无任何文本推断）：
     * 标题/段落/表格走专属结构；页眉页脚跳过；其余类型 description 兜底。
     */
    private String renderNode(DocumentNode node) {
        if (node instanceof TitleNode title) {
            return titleBlock(title);
        }
        if (node instanceof ParagraphNode paragraph) {
            return textBlock(paragraph.getText());
        }
        if (node instanceof TableNode table) {
            return renderTable(table);
        }
        if (node.getType() == DocumentNodeType.HEADER || node.getType() == DocumentNodeType.FOOTER) {
            return null;
        }
        return textBlock(node.getDescription());
    }

    /**
     * 标题块：`## 文本`（固定 2 级，1 级保留给文档头部；标题不可跨行，
     * `\n` 归一为空格——渲染级展平，保留分隔语义）。
     */
    private String titleBlock(TitleNode title) {
        String text = title.getText();
        if (text == null || text.isBlank()) {
            return null;
        }
        return "## " + text.replace('\n', ' ').strip();
    }

    /**
     * 通用文本块（段落/占位 description）：空白则无块，否则原文输出。
     */
    private String textBlock(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        return text;
    }

    /**
     * GFM 表格：rowCount×columnCount 网格，单元格按 rowIndex/columnIndex 定位
     * （rowSpan/colSpan 的覆盖位保持空串，行数 = rowCount 的网格忠实性，不删空行）；
     * 首行为表头行 + `| --- |` 分隔行；rows 为空或列数 ≤0 整表跳过（空表格污染教训）。
     */
    private String renderTable(TableNode table) {
        int rowCount = table.getRowCount();
        int columnCount = table.getColumnCount();
        if (rowCount <= 0 || columnCount <= 0 || table.getRows() == null || table.getRows().isEmpty()) {
            return null;
        }
        String[][] grid = buildGrid(table, rowCount, columnCount);
        StringBuilder sb = new StringBuilder();
        appendRow(sb, grid[0]);
        sb.append('\n');
        appendDelimiter(sb, columnCount);
        for (int r = 1; r < rowCount; r++) {
            sb.append('\n');
            appendRow(sb, grid[r]);
        }
        return sb.toString();
    }

    /**
     * 网格构建：默认空串，单元格显示文本落位于起始坐标（覆盖位不写，见 renderTable）。
     */
    private String[][] buildGrid(TableNode table, int rowCount, int columnCount) {
        String[][] grid = new String[rowCount][columnCount];
        for (String[] row : grid) {
            java.util.Arrays.fill(row, "");
        }
        for (TableRowNode row : table.getRows()) {
            if (row == null || row.getCells() == null) {
                continue;
            }
            for (TableCellNode cell : row.getCells()) {
                if (cell == null || cell.getRowIndex() < 0 || cell.getRowIndex() >= rowCount
                        || cell.getColumnIndex() < 0 || cell.getColumnIndex() >= columnCount) {
                    continue;
                }
                grid[cell.getRowIndex()][cell.getColumnIndex()] = cellText(cell);
            }
        }
        return grid;
    }

    /**
     * 单元格显示文本：值格显示 value、纯表头格显示 header、空格显示 ""（阶段 9 口径）；
     * 转义 `{@code |}` → `\|`、换行 → `&lt;br&gt;`（GFM 单元格换行标准写法）。
     */
    private String cellText(TableCellNode cell) {
        String text = cell.getValue() != null ? cell.getValue() : cell.getHeader();
        if (text == null) {
            return "";
        }
        return text.replace("|", "\\|")
                .replace("\r\n", "\n")
                .replace("\n", "<br>");
    }

    /**
     * 追加一行 `| a | b |`。
     */
    private void appendRow(StringBuilder sb, String[] cells) {
        sb.append('|');
        for (String cell : cells) {
            sb.append(' ').append(cell).append(" |");
        }
    }

    /**
     * 追加 GFM 表头分隔行 `| --- | --- |`。
     */
    private void appendDelimiter(StringBuilder sb, int columnCount) {
        sb.append('|');
        for (int c = 0; c < columnCount; c++) {
            sb.append(" --- |");
        }
    }

    /**
     * KV 语义视图块（硬约束 B）：标记包裹 + `{key}：{value}` 逐行
     * （keyValues 顺序；key 空白跳过；value 换行归一为空格）。
     */
    private String renderKeyValues(List<KeyValueNode> keyValues) {
        List<String> lines = new java.util.ArrayList<>();
        for (KeyValueNode kv : keyValues) {
            if (kv == null || kv.getKey() == null || kv.getKey().isBlank()) {
                continue;
            }
            String value = kv.getValue() == null ? "" : kv.getValue();
            lines.add(kv.getKey() + "：" + value.replace("\r\n", "\n").replace('\n', ' '));
        }
        if (lines.isEmpty()) {
            return null;
        }
        return KV_BEGIN_MARKER + "\n" + String.join("\n", lines) + "\n" + KV_END_MARKER;
    }

    /**
     * 追加非空块，块间以空行分隔（首块前不加）。
     */
    private void appendBlock(StringBuilder sb, String block) {
        if (block == null || block.isEmpty()) {
            return;
        }
        if (sb.length() > 0) {
            sb.append("\n\n");
        }
        sb.append(block);
    }
}
