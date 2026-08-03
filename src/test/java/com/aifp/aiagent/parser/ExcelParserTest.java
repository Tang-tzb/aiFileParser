package com.aifp.aiagent.parser;

import com.aifp.aiagent.document.ParserDocument;
import com.aifp.aiagent.entity.enums.FileType;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link ExcelParser} 解析测试
 * <p>
 * 用 POI 生成 1 sheet 2 行的 xlsx，再解析验证。
 *
 * @author aiFileParser
 */
class ExcelParserTest {

    private final ExcelParser parser = new ExcelParser();

    @Test
    void parse_extractsCellsAndMetadata(@TempDir Path tempDir) throws Exception {
        File xlsxFile = tempDir.resolve("data.xlsx").toFile();

        // 生成 1 sheet 2 行的 xlsx
        try (Workbook wb = new XSSFWorkbook();
             FileOutputStream out = new FileOutputStream(xlsxFile)) {
            Sheet sheet = wb.createSheet("项目表");
            Row header = sheet.createRow(0);
            header.createCell(0).setCellValue("项目名称");
            header.createCell(1).setCellValue("投资金额");
            Row data = sheet.createRow(1);
            data.createCell(0).setCellValue("AI项目");
            data.createCell(1).setCellValue(100.5);
            wb.write(out);
        }

        ParserDocument doc = parser.parse(xlsxFile);

        assertThat(doc.getContent()).contains("项目名称", "投资金额", "AI项目");
        assertThat(doc.getMetadata().getType()).isEqualTo(FileType.EXCEL);
        assertThat(doc.getMetadata().getPage()).isEqualTo(1);
        assertThat(doc.getMetadata().getFileName()).isEqualTo("data.xlsx");
    }
}
