package com.aifp.aiagent.parser.ocr;

import com.aifp.aiagent.parser.pdf.page.ElementSource;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link OcrResult} 纯模型测试：状态语义、行分组、纯文本拼装。
 *
 * @author Tang_tzb
 */
class OcrResultTest {

    private static OcrWord word(String text, int lineNo) {
        return OcrWord.builder()
                .text(text)
                .x(0)
                .y(0)
                .width(10)
                .height(10)
                .confidence(90f)
                .page(1)
                .source(ElementSource.OCR)
                .lineNo(lineNo)
                .build();
    }

    @Test
    void status_successSemantics() {
        OcrResult success = OcrResult.builder()
                .status(OcrStatus.SUCCESS)
                .pages(List.of(OcrPage.builder()
                        .pageNumber(1)
                        .imageWidth(200)
                        .imageHeight(100)
                        .dpi(200)
                        .words(List.of(word("施工", 0), word("许可证", 0), word("编号", 1)))
                        .build()))
                .build();

        assertThat(success.isSuccess()).isTrue();
        assertThat(success.getErrorMessage()).isNull();

        OcrResult failed = OcrResult.builder()
                .status(OcrStatus.FAILED)
                .errorMessage("Tesseract 未安装或路径未配置")
                .pages(List.of())
                .build();
        assertThat(failed.isSuccess()).isFalse();
        assertThat(failed.getErrorMessage()).isNotBlank();

        assertThat(OcrStatus.values())
                .extracting(Enum::name)
                .containsExactlyInAnyOrder("SUCCESS", "EMPTY", "FAILED");
    }

    @Test
    void linesOf_groupsConsecutiveByLineNo() {
        OcrResult result = OcrResult.builder()
                .status(OcrStatus.SUCCESS)
                .pages(List.of(OcrPage.builder()
                        .pageNumber(1)
                        .words(List.of(word("施工", 0), word("许可证", 0), word("编号", 1)))
                        .build()))
                .build();

        List<List<OcrWord>> lines = result.linesOf(1);

        assertThat(lines).hasSize(2);
        assertThat(lines.get(0)).extracting(OcrWord::getText).containsExactly("施工", "许可证");
        assertThat(lines.get(1)).extracting(OcrWord::getText).containsExactly("编号");
        // 不存在的页 → 空行集
        assertThat(result.linesOf(9)).isEmpty();
    }

    @Test
    void toPlainText_joinsWordsAndLinesAcrossPages() {
        OcrPage page1 = OcrPage.builder()
                .pageNumber(1)
                .words(List.of(word("施工", 0), word("许可证", 0), word("编号", 1)))
                .build();
        OcrPage page2 = OcrPage.builder()
                .pageNumber(2)
                .words(List.of(word("第二页", 0)))
                .build();
        OcrResult result = OcrResult.builder()
                .status(OcrStatus.SUCCESS)
                .pages(List.of(page1, page2))
                .build();

        assertThat(result.toPlainText())
                .isEqualTo("施工 许可证\n编号\n第二页");
    }
}
