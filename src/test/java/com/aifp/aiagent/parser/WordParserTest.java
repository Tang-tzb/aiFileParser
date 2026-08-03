package com.aifp.aiagent.parser;

import com.aifp.aiagent.document.ParserDocument;
import com.aifp.aiagent.entity.enums.FileType;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link WordParser} 解析测试
 * <p>
 * 用 POI XWPF 生成 2 段的 docx，再解析验证。
 *
 * @author aiFileParser
 */
class WordParserTest {

    private final WordParser parser = new WordParser();

    @Test
    void parse_extractsParagraphsAndMetadata(@TempDir Path tempDir) throws Exception {
        File docxFile = tempDir.resolve("report.docx").toFile();

        // 生成 2 段的 docx
        try (XWPFDocument docx = new XWPFDocument();
             FileOutputStream out = new FileOutputStream(docxFile)) {
            XWPFParagraph p1 = docx.createParagraph();
            p1.createRun().setText("第一段：项目申报书内容");
            XWPFParagraph p2 = docx.createParagraph();
            p2.createRun().setText("第二段：投资金额说明");
            docx.write(out);
        }

        ParserDocument doc = parser.parse(docxFile);

        assertThat(doc.getContent()).contains("第一段：项目申报书内容", "第二段：投资金额说明");
        assertThat(doc.getMetadata().getType()).isEqualTo(FileType.WORD);
        assertThat(doc.getMetadata().getPage()).isEqualTo(2);
        assertThat(doc.getMetadata().getFileName()).isEqualTo("report.docx");
    }
}
