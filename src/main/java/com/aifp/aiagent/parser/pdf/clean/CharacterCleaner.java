package com.aifp.aiagent.parser.pdf.clean;

import org.springframework.stereotype.Component;

import java.util.regex.Pattern;

/**
 * 字符级清洗器（阶段 10，对应《PDF解析改造方案》§十五/阶段 10"必须解决"前三项）。
 * <p>
 * 职责边界：只做<b>安全字符操作</b>——不可见字符删除、全角空格/制表符/不换行空格
 * 归一为普通空格、行内重复空白折叠。所有来源（PDF_TEXT/OCR/FUSION）均适用：
 * 空白规范化不改变任何非空白字符，PDF_TEXT"零损耗"语义（§十四）保持。
 * <p>
 * 不做的事：不动 {@code \n} 结构（换行连接是 {@link LineCleaner} 的逐行决策领域，
 * 禁止 {@code text.replace("\n","")}）；不改任何非空白字符（OCR 数字纠错归
 * {@link OcrErrorCleaner}）。
 *
 * @author Tang_tzb
 */
@Component
public class CharacterCleaner {

    /**
     * 不可见字符：零宽空格/零宽连接符/零宽非连接符/词连接符/BOM/软连字符
     */
    private static final Pattern INVISIBLE =
            Pattern.compile("[\\u200B\\u200C\\u200D\\u2060\\uFEFF\\u00AD]");

    /**
     * 控制字符（\u0000-\u0008、\u000B、\u000C、\u000E-\u001F、\u007F）；
     * \t(\u0009) 与 \n(\u000A) 不在内——前者归一为空格、后者是结构保留。
     * 注意：\r(\u000D) 已在 clean 前置步骤统一归一为 \n。
     */
    private static final Pattern CONTROL =
            Pattern.compile("[\\u0000-\\u0008\\u000B\\u000C\\u000E-\\u001F\\u007F]");

    /**
     * 行内连续空白（前置归一后仅为普通空格）
     */
    private static final Pattern BLANK_RUN = Pattern.compile(" +");

    /**
     * 全部空白（含 \n，用于比较键归一）
     */
    private static final Pattern ALL_BLANK_RUN = Pattern.compile("[ \\u3000\\u00A0\\t\\n\\r]+");

    /**
     * 字符级清洗（保留 \n 行结构）。
     * <p>
     * 步骤：CRLF 归一 → 不可见字符删除 → 控制字符删除 →
     * \t/全角空格/不换行空格归一 → 逐行折叠重复空白并 trim（保留空行结构）。
     *
     * @param text 原始文本（可 null）
     * @return 清洗后文本；null/空串原样返回
     */
    public String clean(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        // CRLF / 孤立 CR 统一为 LF（防控制字符删除把两行粘连）
        String result = text.replace("\r\n", "\n").replace('\r', '\n');
        result = INVISIBLE.matcher(result).replaceAll("");
        result = CONTROL.matcher(result).replaceAll("");
        result = result.replace('\t', ' ')
                .replace('\u3000', ' ')
                .replace('\u00A0', ' ');
        String[] lines = result.split("\n", -1);
        StringBuilder sb = new StringBuilder(result.length());
        for (int i = 0; i < lines.length; i++) {
            if (i > 0) {
                sb.append('\n');
            }
            sb.append(BLANK_RUN.matcher(lines[i]).replaceAll(" ").trim());
        }
        return sb.toString();
    }

    /**
     * 比较键归一：全部空白（含换行）折叠为单空格并 trim。
     * <p>
     * 供页眉页脚跨页分组与同页去重使用——比较语义下"换行/空格差异"不算内容差异。
     *
     * @param text 原始文本（可 null）
     * @return 归一化比较键；null/空串原样返回
     */
    public String normalize(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        String cleaned = clean(text);
        return ALL_BLANK_RUN.matcher(cleaned).replaceAll(" ").trim();
    }
}
