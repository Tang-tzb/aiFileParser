package com.aifp.aiagent.parser.pdf.clean;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 换行修复清洗器（阶段 10，对应《PDF解析改造方案》§十六）。
 * <p>
 * 决策单位：单节点自身文本内的每个 {@code \n}。AST 已保证同一节点内的行
 * 属于同一段落/单元格/标题——<b>跨节点/跨 Cell 永不合并</b>（§十六"是否跨区域/
 * 是否跨 Cell"以构造性方式回答）。禁止 {@code text.replace("\n","")}：
 * 每个换行独立决策 JOIN/KEEP。
 * <p>
 * 规则（对齐 §十六 判定项"上一行是否以标点结束、下一行是否为标题"）：
 * <ul>
 *   <li>KEEP：上一行以句末标点（。？！）结尾（段落/条目边界）；空行是硬边界；</li>
 *   <li>KEEP（段模式专属守卫）：下一行"标题样"（≤{@code title-like-max-length}
 *       且无句末标点）且上一行以冒号结尾（清单引导后接小标题）；</li>
 *   <li>JOIN：其余一律连接——含子句标点（，、；：）结尾与无标点句中断行
 *       （CJK 断词如 {@code 古\n井大道}）。</li>
 * </ul>
 * 连接细节：Latin 断词去连字符（{@code docu-\nment}）；数字续行直连
 * （两侧均为数字形态字符——数字/半角 O/o，如 {@code 17O\n649.08}，为
 * §十八 OCR 数字纠错先接全 token 提供前提）；CJK 边界无空格直连；
 * 否则单空格连接。句末标点仅取 CJK 全角集（。？！）——Latin 句点保守视为非句末
 * （Latin 换行以空格连接，对 RAG 无损）。
 *
 * @author Tang_tzb
 */
@Component
public class LineCleaner {

    /**
     * 句末标点（保留换行的行尾集合）
     */
    private static final String SENTENCE_TERMINALS = "。？！";

    /**
     * 冒号（清单/条目引导符，标题样守卫的联合条件）
     */
    private static final String COLONS = "：:";

    /**
     * 段模式"标题样"下一行守卫：长度上限（配置 {@code clean.line-join.title-like-max-length}）
     */
    @Value("${document.parser.pdf.clean.line-join.title-like-max-length:20}")
    private int titleLikeMaxLength = 20;

    /**
     * 段落模式换行修复（含标题样守卫）。
     *
     * @param text 段落文本（可 null）
     * @return 修复后文本
     */
    public String joinParagraph(String text) {
        return join(text, true);
    }

    /**
     * 单元格模式换行修复（无标题守卫——单元格是单一逻辑单元，
     * §十六 建设地点多行必须归一为一个 Cell）。
     *
     * @param text 单元格值文本（可 null）
     * @return 修复后文本
     */
    public String joinCell(String text) {
        return join(text, false);
    }

    /**
     * 逐换行决策：prev 取"当前行缓冲的最后一行"（连接后继续增长）。
     */
    private String join(String text, boolean paragraphMode) {
        if (text == null || text.isEmpty() || !text.contains("\n")) {
            return text;
        }
        String[] lines = text.split("\n", -1);
        StringBuilder current = new StringBuilder(text.length()).append(lines[0]);
        for (int i = 1; i < lines.length; i++) {
            String next = lines[i];
            String prevLine = current.substring(current.lastIndexOf("\n") + 1);
            if (shouldJoin(prevLine, next, paragraphMode)) {
                appendJoined(current, prevLine, next);
            } else {
                current.append('\n').append(next);
            }
        }
        return current.toString();
    }

    /**
     * JOIN/KEEP 判定（按序短路）。
     */
    private boolean shouldJoin(String prevLine, String next, boolean paragraphMode) {
        if (prevLine.isEmpty() || next.isEmpty()) {
            return false;
        }
        if (endsWithSentenceTerminal(prevLine)) {
            return false;
        }
        if (paragraphMode && isTitleLike(next) && endsWithColon(prevLine)) {
            return false;
        }
        return true;
    }

    /**
     * 按边界规则连接 prevLine 与 next（先从缓冲摘除 prevLine）。
     */
    private void appendJoined(StringBuilder current, String prevLine, String next) {
        current.setLength(current.length() - prevLine.length());
        if (prevLine.endsWith("-") && !next.isEmpty() && Character.isLetter(next.charAt(0))) {
            current.append(prevLine, 0, prevLine.length() - 1).append(next);
            return;
        }
        // 数字续行直连：断行数字（含 OCR 的 0 形误读 O/o）接全为单 token
        if (isDigitLike(prevLine.charAt(prevLine.length() - 1))
                && isDigitLike(next.charAt(0))) {
            current.append(prevLine).append(next);
            return;
        }
        boolean cjkBoundary = isCjk(prevLine.charAt(prevLine.length() - 1))
                || isCjk(next.charAt(0));
        if (cjkBoundary) {
            current.append(prevLine).append(next);
        } else {
            current.append(prevLine).append(' ').append(next);
        }
    }

    /**
     * 数字形态字符：数字或半角 O/o（'l' 不在列——"word\n5" 直连误合并风险，
     * R4 数字间 l 纠错在接全场景之外仍有 token 内覆盖）。
     */
    private boolean isDigitLike(char c) {
        return Character.isDigit(c) || c == 'O' || c == 'o';
    }

    /**
     * 标题样判定：短且无句末标点。
     */
    private boolean isTitleLike(String line) {
        return line.length() <= titleLikeMaxLength && !endsWithSentenceTerminal(line);
    }

    private boolean endsWithSentenceTerminal(String line) {
        char last = line.charAt(line.length() - 1);
        return SENTENCE_TERMINALS.indexOf(last) >= 0;
    }

    private boolean endsWithColon(String line) {
        char last = line.charAt(line.length() - 1);
        return COLONS.indexOf(last) >= 0;
    }

    /**
     * CJK 字符判定：统一表意文字（含扩展 A/兼容区）、CJK 标点、全角形式。
     * 半角片假名/谚文不在列（连接语义按 Latin 处理，加空格无损）。
     */
    private boolean isCjk(char c) {
        return (c >= 0x3400 && c <= 0x4DBF)      // CJK 扩展 A
                || (c >= 0x4E00 && c <= 0x9FFF)  // CJK 统一表意文字
                || (c >= 0xF900 && c <= 0xFAFF)  // CJK 兼容表意文字
                || (c >= 0x3000 && c <= 0x303F)  // CJK 标点
                || (c >= 0xFF00 && c <= 0xFFEF); // 全角形式
    }
}
