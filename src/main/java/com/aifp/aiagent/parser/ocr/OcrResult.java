package com.aifp.aiagent.parser.ocr;

import lombok.Builder;
import lombok.Data;

import java.util.List;
import java.util.stream.Collectors;

/**
 * OCR 识别结果：状态 + 页集合 + 失败原因。
 * <p>
 * 引擎层以本模型状态化返回（{@link OcrStatus}），不抛业务异常；
 * 错误语义（是否中断流程）由调用方（解析层）决定。
 * 本阶段单图调用含 1 页；多页聚合能力为后续整本 OCR 预留。
 *
 * @author Tang_tzb
 */
@Data
@Builder
public class OcrResult {

    /**
     * 识别状态
     */
    private OcrStatus status;

    /**
     * 失败原因（仅 FAILED 时非空）
     */
    private String errorMessage;

    /**
     * 页结果（FAILED 时为空列表）
     */
    private List<OcrPage> pages;

    /**
     * 词序列按 lineNo 变化沿切分为连续行段。
     */
    private static List<List<OcrWord>> groupConsecutiveByLine(List<OcrWord> words) {
        List<List<OcrWord>> lines = new java.util.ArrayList<>();
        List<OcrWord> current = null;
        int currentLineNo = Integer.MIN_VALUE;
        for (OcrWord word : words) {
            if (current == null || word.getLineNo() != currentLineNo) {
                current = new java.util.ArrayList<>();
                lines.add(current);
                currentLineNo = word.getLineNo();
            }
            current.add(word);
        }
        return lines;
    }

    /**
     * 是否识别成功
     */
    public boolean isSuccess() {
        return status == OcrStatus.SUCCESS;
    }

    /**
     * 按行分组指定页的词（lineNo 连续段 = 一行；仅引擎原始布局元数据分组）。
     */
    public List<List<OcrWord>> linesOf(int pageNumber) {
        return pages.stream()
                .filter(p -> p.getPageNumber() == pageNumber)
                .flatMap(p -> p.getWords().stream())
                .collect(Collectors.collectingAndThen(
                        Collectors.toList(),
                        OcrResult::groupConsecutiveByLine));
    }

    /**
     * 纯文本：按页、按行拼接（行内词空格连接）。
     */
    public String toPlainText() {
        return pages.stream()
                .flatMap(p -> linesOf(p.getPageNumber()).stream())
                .map(line -> line.stream()
                        .map(OcrWord::getText)
                        .collect(Collectors.joining(" ")))
                .collect(Collectors.joining("\n"));
    }
}
