package com.aifp.aiagent.parser.ocr;

import com.aifp.aiagent.parser.pdf.page.ElementSource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/**
 * Tesseract OCR 解析器：阶段 4 标准化词级识别实现（Tesseract CLI + TSV 输出）。
 * <p>
 * 职责边界（对齐《PDF解析改造方案》）：
 * <ul>
 *   <li>Tesseract 负责实际文字识别（本类，词级坐标 + 置信度）；</li>
 *   <li>OCRmyPDF 为后续可选的 PDF OCR 工作流组件（整本扫描 PDF → 可搜索 PDF、
 *       deskew/clean 预处理、OCR 文字层生成），不作为 IMAGE_ONLY 页的词级
 *       OCR 核心，待存在真实消费方的阶段接入。</li>
 * </ul>
 * 跨平台兼容（Windows/Linux/Docker）：{@code tesseract-path} 默认为裸命令名
 * （PATH 解析，三平台通吃）；平台差异经环境变量覆盖（OCR_TESSERACT_PATH /
 * TESSDATA_PREFIX 按 Tesseract 原生约定），配置不写死任何平台默认路径。
 * <p>
 * 异常封闭：所有进程异常、超时、中断均收敛为 {@link OcrStatus#FAILED}
 * + errorMessage，不向上层泄漏底层异常；并发固定 1（2CPU/8GB 资源限制）。
 *
 * @author Tang_tzb
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "document.parser.pdf.ocr.engine", havingValue = "tesseract", matchIfMissing = true)
public class TesseractOcrParser implements OcrParser {

    /**
     * 识别并发闸门：同步调用天然串行，信号量硬约束防上层误并发。
     */
    private final Semaphore permits = new Semaphore(1);
    /**
     * 识别引擎可执行文件：默认走 PATH（裸命令名），平台差异经环境变量覆盖
     */
    @Value("${document.parser.pdf.ocr.tesseract-path:tesseract}")
    private String tesseractPath = "tesseract";
    /**
     * 识别语言（默认中文简体）
     */
    @Value("${document.parser.pdf.ocr.language:chi_sim}")
    private String language = "chi_sim";
    /**
     * 页面分割模式（3=自动布局）
     */
    @Value("${document.parser.pdf.ocr.psm:3}")
    private int psm = 3;
    /**
     * 单页识别超时（秒）
     */
    @Value("${document.parser.pdf.ocr.timeout-seconds:300}")
    private int timeoutSeconds = 300;
    /**
     * 识别并发上限（资源限制：2CPU/8GB → 固定 1）
     */
    @Value("${document.parser.pdf.ocr.concurrency:1}")
    private int concurrency = 1;

    /**
     * TSV 解析（纯函数）：仅取 level=5（词）、conf≥0、text 非空白的行；
     * (block,par,line) 三元组变化沿递增 lineNo。畸形行（缺列/非数值）直接抛出
     * NumberFormatException / IllegalArgumentException，由 {@link #recognize}
     * 统一收敛为 FAILED（不向上层泄漏底层异常类型）。
     *
     * @param tsv        Tesseract TSV 输出
     * @param pageNumber 页码（1-based）
     * @return 词列表（阅读序）
     */
    static List<OcrWord> parseTsv(String tsv, int pageNumber) {
        List<OcrWord> words = new ArrayList<>();
        int lineNo = -1;
        int prevBlock = -1;
        int prevPar = -1;
        int prevLine = -1;
        boolean headerSkipped = false;
        for (String raw : tsv.split("\n")) {
            // 仅去除回车：保留行尾空列（level 1-4 行的 text 列为空，trim 会破坏列数）
            String line = raw.replace("\r", "");
            if (line.isBlank()) {
                continue;
            }
            String[] cols = line.split("\t", -1);
            if (!headerSkipped && cols.length > 0 && "level".equals(cols[0])) {
                headerSkipped = true;
                continue;
            }
            if (cols.length < 12) {
                throw new IllegalArgumentException("TSV 列数不足: " + line);
            }
            int level = Integer.parseInt(cols[0]);
            if (level != 5) {
                continue;
            }
            String text = cols[11];
            if (text == null || text.isBlank()) {
                continue;
            }
            float confidence = Float.parseFloat(cols[10]);
            if (confidence < 0) {
                continue;
            }
            int block = Integer.parseInt(cols[2]);
            int par = Integer.parseInt(cols[3]);
            int lineNum = Integer.parseInt(cols[4]);
            if (block != prevBlock || par != prevPar || lineNum != prevLine) {
                lineNo++;
                prevBlock = block;
                prevPar = par;
                prevLine = lineNum;
            }
            words.add(OcrWord.builder()
                    .text(text)
                    .x(Integer.parseInt(cols[6]))
                    .y(Integer.parseInt(cols[7]))
                    .width(Integer.parseInt(cols[8]))
                    .height(Integer.parseInt(cols[9]))
                    .confidence(confidence)
                    .page(pageNumber)
                    .source(ElementSource.OCR)
                    .lineNo(lineNo)
                    .build());
        }
        return words;
    }

    /**
     * 构造失败结果（pages 为空列表）。
     */
    private static OcrResult failed(String message) {
        return OcrResult.builder()
                .status(OcrStatus.FAILED)
                .errorMessage(message)
                .pages(List.of())
                .build();
    }

    /**
     * stderr 摘要截断（避免 errorMessage 过长）。
     */
    private static String abbreviate(String text) {
        if (text == null) {
            return "";
        }
        String trimmed = text.trim();
        return trimmed.length() <= 300 ? trimmed : trimmed.substring(0, 300) + "...";
    }

    @Override
    public OcrResult recognize(OcrRequest request) {
        boolean acquired = false;
        try {
            permits.acquire();
            acquired = true;
            return doRecognize(request);
        } catch (InterruptedException e) {
            // 恢复中断标志后按失败返回，不向上层泄漏 InterruptedException
            Thread.currentThread().interrupt();
            return failed("OCR 识别被中断: 第" + request.getPageNumber() + "页");
        } catch (Exception e) {
            // 兜底收敛：任何引擎层异常不得向上层泄漏
            log.warn("OCR 识别异常 page={}", request.getPageNumber(), e);
            return failed("OCR 识别异常: " + e.getMessage());
        } finally {
            if (acquired) {
                permits.release();
            }
        }
    }

    /**
     * 执行单页识别：引擎路径解析 → 进程调用 → TSV 解析 → 状态归集。
     * 启动失败的 IOException 在此转为"未安装或路径未配置"的明确失败信息；
     * 其余异常（超时中断/读取失败/解析失败）上抛，由 {@link #recognize} 收敛。
     */
    private OcrResult doRecognize(OcrRequest request) throws IOException, InterruptedException {
        String executable = resolveExecutable();
        if (executable == null) {
            return failed("Tesseract 未安装或路径未配置: " + tesseractPath);
        }
        Process process;
        try {
            process = startProcess(executable, request.getImageFile());
        } catch (IOException e) {
            // 进程启动失败 = 引擎缺失/权限问题（裸命令名不在 PATH 等）
            return failed("Tesseract 未安装或路径未配置（命令: " + executable + "）: " + e.getMessage());
        }
        try {
            String stdout = readStream(process);
            boolean finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                return failed("OCR 识别超时(" + timeoutSeconds + "s): 第" + request.getPageNumber() + "页");
            }
            if (process.exitValue() != 0) {
                return failed("OCR 引擎退出码 " + process.exitValue()
                        + ": 第" + request.getPageNumber() + "页, stderr=" + abbreviate(readErrorStream(process)));
            }
            List<OcrWord> words = parseTsv(stdout, request.getPageNumber());
            OcrStatus status = words.isEmpty() ? OcrStatus.EMPTY : OcrStatus.SUCCESS;
            OcrPage page = OcrPage.builder()
                    .pageNumber(request.getPageNumber())
                    .imageWidth(request.getImageWidth())
                    .imageHeight(request.getImageHeight())
                    .dpi(request.getDpi())
                    .words(words)
                    .build();
            return OcrResult.builder()
                    .status(status)
                    .pages(List.of(page))
                    .build();
        } finally {
            process.destroyForcibly();
        }
    }

    /**
     * 跨平台引擎路径解析（三环境统一逻辑，不写死平台默认路径）：
     * <ul>
     *   <li>配置值为已存在的文件（绝对/相对路径）→ 直接使用；</li>
     *   <li>配置值含路径分隔符但文件不存在 → 返回 null（明确报错，不做无意义 PATH 尝试）；</li>
     *   <li>裸命令名（默认 {@code tesseract}）→ 原样返回，交 ProcessBuilder 走 PATH
     *       （Windows 的 CreateProcess 自动补 .exe 并搜索 PATH，Linux/Docker 同样走 PATH）。</li>
     * </ul>
     */
    private String resolveExecutable() {
        if (new File(tesseractPath).isFile()) {
            return tesseractPath;
        }
        boolean isPathLike = tesseractPath.contains("/") || tesseractPath.contains("\\")
                || tesseractPath.contains(File.separator);
        return isPathLike ? null : tesseractPath;
    }

    /**
     * 启动 Tesseract 进程（TSV 输出到 stdout）。启动失败（引擎缺失/权限）上抛
     * IOException，由 {@link #recognize} 统一收敛为 FAILED。
     */
    private Process startProcess(String executable, File imageFile) throws IOException {
        ProcessBuilder pb = new ProcessBuilder(
                executable, imageFile.getAbsolutePath(), "stdout",
                "-l", language, "--psm", String.valueOf(psm), "tsv");
        pb.redirectErrorStream(false);
        return pb.start();
    }

    /**
     * 读取进程 stdout（Tesseract TSV 输出为 UTF-8）。
     */
    private String readStream(Process process) throws IOException {
        StringBuilder sb = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line).append('\n');
            }
        }
        return sb.toString();
    }

    /**
     * 读取进程 stderr（仅在进程已结束后调用，用于失败原因摘要）。
     */
    private String readErrorStream(Process process) {
        StringBuilder sb = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getErrorStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line).append('\n');
            }
        } catch (IOException e) {
            return "<stderr 读取失败>";
        }
        return sb.toString();
    }
}
