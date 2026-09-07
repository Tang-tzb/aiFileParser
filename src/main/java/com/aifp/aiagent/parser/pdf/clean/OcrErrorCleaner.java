package com.aifp.aiagent.parser.pdf.clean;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * OCR 数字纠错清洗器（阶段 10，对应《PDF解析改造方案》§十八）。
 * <p>
 * <b>严格上下文门控</b>——只处理"数字形态"高置信度场景，禁止全局 {@code O→0}：
 * <ul>
 *   <li>R1 全角数字/句点：{@code ０-９→0-9}；数字间全角句点 {@code ．→.}；</li>
 *   <li>R2 数字间 O：{@code (?<=\d)[Oo](?=\d) → 0}（{@code 17O649.08→170649.08}）；</li>
 *   <li>R3 纯数字形 token 首/尾 O：{@code O823→0823}、{@code 3O→30}（token 须整体
 *       呈数字形，含字母语境如 {@code Office/GPU} 永不触碰）；</li>
 *   <li>R4 数字间 l：{@code (?<=\d)l(?=\d) → 1}。</li>
 * </ul>
 * 适用面由<b>调用方把关</b>：仅 source=OCR 的文本（OCR 段落、表格 header、KV key）；
 * PDF_TEXT 与 FUSION value 为原生文字零损耗（§十四），永不传入。
 * {@code I→1} 歧义过高，不做。
 *
 * @author Tang_tzb
 */
@Component
public class OcrErrorCleaner {

    /**
     * 纯数字形 token（用于首/尾 O 判定）：可选小数部分
     */
    private static final Pattern LEADING_O_TOKEN = Pattern.compile("^[Oo]\\d+([.,]\\d+)?$");
    private static final Pattern TRAILING_O_TOKEN = Pattern.compile("^\\d+([.,]\\d+)?[Oo]$");
    /**
     * token 切分（空白/换行分隔，保留分隔结构）
     */
    private static final Pattern TOKEN = Pattern.compile("\\S+");
    /**
     * 数字间全角句点
     */
    private static final Pattern FULLWIDTH_DOT_IN_NUMBER =
            Pattern.compile("(?<=\\d)．(?=\\d)");
    /**
     * 纠错总开关（配置 {@code clean.ocr-digit-fix.enabled}）
     */
    @Value("${document.parser.pdf.clean.ocr-digit-fix.enabled:true}")
    private boolean enabled = true;

    /**
     * OCR 数字纠错主入口。
     *
     * @param text OCR 来源文本（可 null）
     * @return 纠错后文本；关闭开关或 null/空串原样返回
     */
    public String fix(String text) {
        if (!enabled || text == null || text.isEmpty()) {
            return text;
        }
        // R1：全角数字 → 半角（OCR 不应产出全角数字，语义等价转换）
        StringBuilder sb = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c >= '０' && c <= '９') {
                sb.append((char) (c - '０' + '0'));
            } else {
                sb.append(c);
            }
        }
        String result = sb.toString();
        // R1b：数字间全角句点 → 半角
        result = FULLWIDTH_DOT_IN_NUMBER.matcher(result).replaceAll(".");
        // R2：数字间 O/o → 0
        result = result.replaceAll("(?<=\\d)[Oo](?=\\d)", "0");
        // R4：数字间 l → 1
        result = result.replaceAll("(?<=\\d)l(?=\\d)", "1");
        // R3：纯数字形 token 首/尾 O
        return fixTokenEdges(result);
    }

    /**
     * R3：token 级首/尾 O 纠正（保持空白分隔结构不变）。
     */
    private String fixTokenEdges(String text) {
        Matcher m = TOKEN.matcher(text);
        StringBuilder sb = new StringBuilder(text.length());
        while (m.find()) {
            String token = m.group();
            if (LEADING_O_TOKEN.matcher(token).matches()
                    || TRAILING_O_TOKEN.matcher(token).matches()) {
                token = token.replace('O', '0').replace('o', '0');
            }
            m.appendReplacement(sb, Matcher.quoteReplacement(token));
        }
        m.appendTail(sb);
        return sb.toString();
    }
}
