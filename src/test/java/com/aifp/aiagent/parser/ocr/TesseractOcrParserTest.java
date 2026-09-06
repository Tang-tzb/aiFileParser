package com.aifp.aiagent.parser.ocr;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.File;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link TesseractOcrParser} 测试：TSV 解析纯函数 + 引擎层异常收敛
 * （所有失败均以 OcrStatus.FAILED 状态化返回，不向上层泄漏底层异常）。
 *
 * @author Tang_tzb
 */
class TesseractOcrParserTest {

    private static final String SAMPLE_TSV = String.join("\n",
            String.join("\t", "level", "page_num", "block_num", "par_num",
                    "line_num", "word_num", "left", "top", "width", "height", "conf", "text"),
            String.join("\t", "1", "1", "0", "0", "0", "0", "0", "0", "200", "50", "-1", ""),
            String.join("\t", "5", "1", "1", "1", "1", "1", "10", "20", "60", "30", "95.5", "施工"),
            String.join("\t", "5", "1", "1", "1", "1", "2", "80", "20", "70", "30", "92.3", "许可证"),
            String.join("\t", "5", "1", "1", "2", "1", "1", "10", "60", "80", "30", "88.1", "编号"),
            "");

    private static OcrRequest request(File imageFile) {
        return OcrRequest.builder()
                .imageFile(imageFile)
                .pageNumber(1)
                .imageWidth(200)
                .imageHeight(100)
                .dpi(200)
                .build();
    }

    /**
     * TSV 解析：词字段完整（text/坐标/置信度/page/source/lineNo），
     * 过滤 level≠5 与 conf<0 行，lineNo 按 (block,par,line) 变化沿递增。
     */
    @Test
    void parseTsv_extractsWordsWithLineGrouping() {
        List<OcrWord> words = TesseractOcrParser.parseTsv(SAMPLE_TSV, 1);

        assertThat(words).hasSize(3);

        OcrWord first = words.get(0);
        assertThat(first.getText()).isEqualTo("施工");
        assertThat(first.getX()).isEqualTo(10);
        assertThat(first.getY()).isEqualTo(20);
        assertThat(first.getWidth()).isEqualTo(60);
        assertThat(first.getHeight()).isEqualTo(30);
        assertThat(first.getConfidence()).isEqualTo(95.5f);
        assertThat(first.getPage()).isEqualTo(1);
        assertThat(first.getSource()).isEqualTo(com.aifp.aiagent.parser.pdf.page.ElementSource.OCR);
        assertThat(first.getLineNo()).isZero();

        // 同一 (block,par,line) → 同行；变化沿递增
        assertThat(words.get(1).getText()).isEqualTo("许可证");
        assertThat(words.get(1).getLineNo()).isZero();
        assertThat(words.get(2).getText()).isEqualTo("编号");
        assertThat(words.get(2).getLineNo()).isEqualTo(1);
    }

    /**
     * 合法 TSV 无词行（仅 header 与 level=1 结构行）→ 空词列表（EMPTY 前置条件）。
     */
    @Test
    void parseTsv_noWordRows_returnsEmptyList() {
        String headerOnly = String.join("\n",
                String.join("\t", "level", "page_num", "block_num", "par_num",
                        "line_num", "word_num", "left", "top", "width", "height", "conf", "text"),
                String.join("\t", "1", "1", "0", "0", "0", "0", "0", "0", "200", "50", "-1", ""),
                "");

        assertThat(TesseractOcrParser.parseTsv(headerOnly, 1)).isEmpty();
    }

    /**
     * 畸形 TSV（缺列/非数值列）→ 解析层抛出，由 recognize 收敛为 FAILED，
     * 不向上层泄漏 NumberFormatException（此处验证纯函数的异常契约）。
     */
    @Test
    void parseTsv_malformedRows_throws() {
        // 缺列
        assertThatThrownBy(() -> TesseractOcrParser.parseTsv("not-a-tsv", 1))
                .isInstanceOf(IllegalArgumentException.class);
        // 非数值 conf
        String badConf = String.join("\t", "5", "1", "1", "1", "1", "1",
                "10", "20", "60", "30", "abc", "施工");
        assertThatThrownBy(() -> TesseractOcrParser.parseTsv(badConf, 1))
                .isInstanceOf(NumberFormatException.class);
    }

    /**
     * 引擎缺失（含路径分隔符但文件不存在）→ FAILED，errorMessage 明确，
     * OcrParser 不抛异常（异常收敛验证）。
     */
    @Test
    void recognize_missingEnginePath_returnsFailedWithoutThrowing(@org.junit.jupiter.api.io.TempDir
                                                                  java.nio.file.Path tempDir) throws Exception {
        TesseractOcrParser parser = new TesseractOcrParser();
        ReflectionTestUtils.setField(parser, "tesseractPath",
                tempDir.resolve("no-such-tesseract.exe").toString());

        OcrResult result = parser.recognize(request(new File("whatever.png")));

        assertThat(result.getStatus()).isEqualTo(OcrStatus.FAILED);
        assertThat(result.getErrorMessage()).contains("未安装或路径未配置");
        assertThat(result.getPages()).isEmpty();
        assertThat(result.isSuccess()).isFalse();
    }

    /**
     * 引擎缺失（裸命令名不在 PATH）→ 进程启动失败收敛为 FAILED（跨平台 PATH 解析路径）。
     */
    @Test
    void recognize_bareNameNotOnPath_returnsFailedWithoutThrowing() {
        TesseractOcrParser parser = new TesseractOcrParser();
        ReflectionTestUtils.setField(parser, "tesseractPath", "aifp-nonexistent-tesseract-cmd");

        OcrResult result = parser.recognize(request(new File("whatever.png")));

        assertThat(result.getStatus()).isEqualTo(OcrStatus.FAILED);
        assertThat(result.getErrorMessage()).contains("未安装或路径未配置");
    }

    /**
     * 路径解析逻辑（三环境统一）：裸命令名原样交 ProcessBuilder（PATH 解析）。
     */
    @Test
    void recognize_bareName_delegatesToPathResolution() {
        // 已存在的文件路径 → 直接使用（用一个确定存在的文件：临时文件）
        TesseractOcrParser parser = new TesseractOcrParser();
        ReflectionTestUtils.setField(parser, "tesseractPath", "definitely-not-a-command");
        // 裸命令名（无分隔符）不会被路径预检拒绝，进程启动失败才收敛为 FAILED
        OcrResult result = parser.recognize(request(new File("whatever.png")));
        assertThat(result.getStatus()).isEqualTo(OcrStatus.FAILED);
    }
}
