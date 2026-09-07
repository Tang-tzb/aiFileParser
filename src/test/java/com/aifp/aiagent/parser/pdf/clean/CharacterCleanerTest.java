package com.aifp.aiagent.parser.pdf.clean;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link CharacterCleaner} 单元测试（阶段 10）：
 * 不可见字符、全角空格/制表符归一、行内重复空白折叠、\n 结构保留、比较键归一。
 *
 * @author Tang_tzb
 */
class CharacterCleanerTest {

    private CharacterCleaner cleaner;

    @BeforeEach
    void setUp() {
        cleaner = new CharacterCleaner();
    }

    @Test
    void nullAndEmpty_passthrough() {
        assertThat(cleaner.clean(null)).isNull();
        assertThat(cleaner.clean("")).isEmpty();
        assertThat(cleaner.normalize(null)).isNull();
        assertThat(cleaner.normalize("")).isEmpty();
    }

    @Test
    void invisibleChars_removed() {
        // 零宽空格/零宽连接符/词连接符/BOM/软连字符全部删除，不产生空格
        assertThat(cleaner.clean("建\u200B设\u200C单\u2060位\uFEFF单\u00AD位"))
                .isEqualTo("建设单位单位");
    }

    @Test
    void controlChars_removed_lineFeedPreserved() {
        // 控制字符删除；\r\n 与孤立 \r 归一为 \n（防两行粘连）
        assertThat(cleaner.clean("a\u0000b\u0007c")).isEqualTo("abc");
        assertThat(cleaner.clean("第一行\r\n第二行\r第三行")).isEqualTo("第一行\n第二行\n第三行");
    }

    @Test
    void fullWidthSpaceAndTab_normalizedToSpace() {
        // \u3000 全角空格、\t、\u00A0 不换行空格 → 普通空格 → 折叠
        assertThat(cleaner.clean("甲\u3000\u3000乙")).isEqualTo("甲 乙");
        assertThat(cleaner.clean("甲\t乙\u00A0丙")).isEqualTo("甲 乙 丙");
    }

    @Test
    void repeatedBlanks_collapsedPerLine() {
        assertThat(cleaner.clean("a   b\t\tc")).isEqualTo("a b c");
    }

    @Test
    void newLineStructure_preservedAndLinesTrimmed() {
        // 每行独立 trim + 折叠；\n 数量不变（含空行结构保留）
        assertThat(cleaner.clean("  第一行  \n   \n  第 三 行  "))
                .isEqualTo("第一行\n\n第 三 行");
    }

    @Test
    void nonBlankChars_untouched() {
        // 非空白字符零改动（含全角标点/字母/数字——OCR 纠错不在此层）
        String raw = "合同工期：１７０６４９。０８平方米（Office,GPU）";
        assertThat(cleaner.clean(raw)).isEqualTo(raw);
    }

    @Test
    void normalize_collapsesAllBlanksIncludingNewLines() {
        // 比较键：换行/多空白差异不算内容差异（仅折叠空白，不插入/删除非空白字符）
        assertThat(cleaner.normalize("建设  单位\n名称")).isEqualTo("建设 单位 名称");
        assertThat(cleaner.normalize("建设单位名称")).isEqualTo("建设单位名称");
        assertThat(cleaner.normalize("  \n  ")).isEmpty();
    }
}
