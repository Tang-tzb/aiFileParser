package com.aifp.aiagent.parser.pdf.clean;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link LineCleaner} 单元测试（阶段 10）：
 * 段/Cell 两模式 JOIN/KEEP、CJK 断词连接、Latin 去连字符、标题样守卫、空行硬边界。
 *
 * @author Tang_tzb
 */
class LineCleanerTest {

    private LineCleaner cleaner;

    @BeforeEach
    void setUp() {
        cleaner = new LineCleaner();
        ReflectionTestUtils.setField(cleaner, "titleLikeMaxLength", 20);
    }

    @Test
    void nullOrSingleLine_passthrough() {
        assertThat(cleaner.joinParagraph(null)).isNull();
        assertThat(cleaner.joinParagraph("单行文本")).isEqualTo("单行文本");
        assertThat(cleaner.joinCell("")).isEmpty();
    }

    @Test
    void clausePunctuation_joined() {
        // 子句标点（，、；）结尾 → 连接（真实样例 建设规模 断行形态）
        assertThat(cleaner.joinParagraph("南地块位于养生大道以南，\n古井大道以东；北地块"))
                .isEqualTo("南地块位于养生大道以南，古井大道以东；北地块");
    }

    @Test
    void cjkMidWordBreak_joinedWithoutSpace() {
        // 无标点 CJK 句中断行 → 无空格直连（古\n井大道 断词修复）
        assertThat(cleaner.joinParagraph("谯城经开区古\n井大道以东，桐花路以南"))
                .isEqualTo("谯城经开区古井大道以东，桐花路以南");
    }

    @Test
    void multiLineContinuousJoin_allCollapsed() {
        // 连续子句断行逐级接全
        assertThat(cleaner.joinCell("A区位于路南，\nB区位于路北；\nC区待建"))
                .isEqualTo("A区位于路南，B区位于路北；C区待建");
    }

    @Test
    void sentenceTerminal_kept() {
        // 句末标点（。？！）→ 保留换行（段落/条目边界）
        assertThat(cleaner.joinParagraph("第一段结束。\n第二段开始"))
                .isEqualTo("第一段结束。\n第二段开始");
        assertThat(cleaner.joinCell("第一条内容！\n第二条内容"))
                .isEqualTo("第一条内容！\n第二条内容");
    }

    @Test
    void paragraphMode_titleLikeAfterColon_kept() {
        // 段模式：下一行标题样 + 上一行冒号结尾（清单引导）→ 保留
        assertThat(cleaner.joinParagraph("设备清单如下：\n主要设备"))
                .isEqualTo("设备清单如下：\n主要设备");
    }

    @Test
    void paragraphMode_titleLikeButMidClause_stillJoined() {
        // 段模式：下一行虽短但上一行句中未完 → 连接（守卫不得破坏断词修复）
        assertThat(cleaner.joinParagraph("城南侧的施工\n范围"))
                .isEqualTo("城南侧的施工范围");
    }

    @Test
    void cellMode_noTitleGuard_alwaysJoined() {
        // Cell 模式：无标题守卫——单元格是单一逻辑单元（§十六）
        assertThat(cleaner.joinCell("设备清单如下：\n主要设备"))
                .isEqualTo("设备清单如下：主要设备");
    }

    @Test
    void latinDehyphenation_joined() {
        // Latin 断词：去连字符直连
        assertThat(cleaner.joinParagraph("docu-\nment of record"))
                .isEqualTo("document of record");
    }

    @Test
    void latinToLatin_joinedWithSpace() {
        assertThat(cleaner.joinParagraph("Hello\nworld again"))
                .isEqualTo("Hello world again");
    }

    @Test
    void digitContinuation_joinedWithoutSpace() {
        // 数字续行直连：断行数字（含 OCR 0 形误读 O）接全为单 token，
        // 供 OcrErrorCleaner 在后级纠错（17O649.08 → 170649.08）
        assertThat(cleaner.joinParagraph("17O\n649.08")).isEqualTo("17O649.08");
        assertThat(cleaner.joinParagraph("17\n0649")).isEqualTo("170649");
    }

    @Test
    void wordThenNumber_stillJoinedWithSpace() {
        // 词-数字边界非数字续行：保留空格（防 "Room\n101" 误合并）
        assertThat(cleaner.joinParagraph("Room\n101 is ready"))
                .isEqualTo("Room 101 is ready");
    }

    @Test
    void blankLine_isHardBoundary() {
        // 空行为硬边界：两侧均不连接，空行结构保留
        assertThat(cleaner.joinParagraph("上段文字\n\n下段文字"))
                .isEqualTo("上段文字\n\n下段文字");
    }

    @Test
    void mixedBoundaries_correctDecisions() {
        // 混合：句末保留 + 断词连接 + 子句连接
        assertThat(cleaner.joinCell("总述完毕。\n其中南地块位于路南，\n北地块位于路北"))
                .isEqualTo("总述完毕。\n其中南地块位于路南，北地块位于路北");
    }
}
