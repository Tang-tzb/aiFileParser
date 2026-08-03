package com.aifp.aiagent.parser;

import com.aifp.aiagent.common.ResultCode;
import com.aifp.aiagent.document.ParserDocument;
import com.aifp.aiagent.document.ParserDocumentMetadata;
import com.aifp.aiagent.entity.enums.FileType;
import com.aifp.aiagent.exception.BusinessException;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.ss.usermodel.*;
import org.springframework.stereotype.Component;

import java.io.File;
import java.io.IOException;

/**
 * Excel 解析器（Apache POI）
 * <p>
 * 使用 {@link WorkbookFactory} 自动识别 xls/xlsx，按"工作表→行"以制表符拼装文本，
 * {@code page=工作表数}，便于后续 AI 字段抽取。
 *
 * @author aiFileParser
 */
@Slf4j
@Component
public class ExcelParser implements FileParser {

    private static final String SHEET_SEP = "\n\n";
    private static final String ROW_SEP = "\n";
    private static final String CELL_SEP = "\t";

    @Override
    public FileType supportedType() {
        return FileType.EXCEL;
    }

    @Override
    public ParserDocument parse(File file) {
        try (Workbook workbook = WorkbookFactory.create(file)) {
            DataFormatter formatter = new DataFormatter();
            StringBuilder sb = new StringBuilder();

            for (int i = 0; i < workbook.getNumberOfSheets(); i++) {
                Sheet sheet = workbook.getSheetAt(i);
                if (i > 0) {
                    sb.append(SHEET_SEP);
                }
                sb.append("[Sheet: ").append(sheet.getSheetName()).append("]").append(ROW_SEP);
                appendSheetText(sheet, formatter, sb);
            }

            ParserDocumentMetadata metadata = ParserDocumentMetadata.builder()
                    .fileName(file.getName())
                    .page(workbook.getNumberOfSheets())
                    .type(FileType.EXCEL)
                    .build();

            ParserDocument doc = new ParserDocument();
            doc.setContent(sb.toString());
            doc.setMetadata(metadata);
            log.info("Excel 解析完成 file={}, sheets={}, contentLen={}",
                    file.getName(), workbook.getNumberOfSheets(), sb.length());
            return doc;
        } catch (IOException e) {
            log.error("Excel 解析失败: {}", file.getName(), e);
            throw new BusinessException(ResultCode.FILE_PARSE_ERROR, "Excel 解析失败: " + file.getName());
        }
    }

    /**
     * 将一个工作表的行拼装为文本
     */
    private void appendSheetText(Sheet sheet, DataFormatter formatter, StringBuilder sb) {
        for (Row row : sheet) {
            StringBuilder rowBuilder = new StringBuilder();
            boolean hasCell = false;
            for (Cell cell : row) {
                if (hasCell) {
                    rowBuilder.append(CELL_SEP);
                }
                rowBuilder.append(formatter.formatCellValue(cell));
                hasCell = true;
            }
            if (hasCell) {
                sb.append(rowBuilder).append(ROW_SEP);
            }
        }
    }
}
