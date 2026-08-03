package com.aifp.aiagent.parser;

import com.aifp.aiagent.entity.enums.FileType;
import com.aifp.aiagent.exception.BusinessException;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link FileParserRegistry} 策略选择测试
 *
 * @author aiFileParser
 */
class FileParserRegistryTest {

    private final FileParserRegistry registry =
            new FileParserRegistry(List.of(new PdfParser(), new ExcelParser(), new WordParser()));

    @Test
    void getPdf_returnsPdfParser() {
        FileParser parser = registry.get(FileType.PDF);
        assertThat(parser).isInstanceOf(PdfParser.class);
    }

    @Test
    void getExcel_returnsExcelParser() {
        FileParser parser = registry.get(FileType.EXCEL);
        assertThat(parser).isInstanceOf(ExcelParser.class);
    }

    @Test
    void getWord_returnsWordParser() {
        FileParser parser = registry.get(FileType.WORD);
        assertThat(parser).isInstanceOf(WordParser.class);
    }

    @Test
    void getUnsupportedType_throws2002() {
        // TXT 未实现解析器 → 抛 FILE_TYPE_NOT_SUPPORT
        assertThatThrownBy(() -> registry.get(FileType.TXT))
                .isInstanceOf(BusinessException.class);
    }
}
