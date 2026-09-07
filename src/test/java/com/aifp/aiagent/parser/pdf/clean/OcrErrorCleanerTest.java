package com.aifp.aiagent.parser.pdf.clean;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link OcrErrorCleaner} 单元测试（阶段 10，§十八）：
 * 上下文门控数字纠错 + 禁止全局 O→0 的负面用例。
 *
 * @author Tang_tzb
 */
class OcrErrorCleanerTest {

    private OcrErrorCleaner cleaner;

    @BeforeEach
    void setUp() {
        cleaner = new OcrErrorCleaner();
        ReflectionTestUtils.setField(cleaner, "enabled", true);
    }

    @Test
    void nullEmptyOrDisabled_passthrough() {
        assertThat(cleaner.fix(null)).isNull();
        assertThat(cleaner.fix("")).isEmpty();
        ReflectionTestUtils.setField(cleaner, "enabled", false);
        assertThat(cleaner.fix("17O649.08")).isEqualTo("17O649.08");
    }

    @Test
    void oBetweenDigits_becomesZero() {
        // 主方案示例：17O649.08 → 170649.08
        assertThat(cleaner.fix("17O649.08")).isEqualTo("170649.08");
        assertThat(cleaner.fix("面积 1O50 平方米")).isEqualTo("面积 1050 平方米");
        assertThat(cleaner.fix("3o2")).isEqualTo("302");
    }

    @Test
    void tokenEdgeO_becomesZero() {
        // 纯数字形 token 的首/尾 O
        assertThat(cleaner.fix("O823")).isEqualTo("0823");
        assertThat(cleaner.fix("3O")).isEqualTo("30");
        assertThat(cleaner.fix("12.5o")).isEqualTo("12.50");
    }

    @Test
    void lBetweenDigits_becomesOne() {
        assertThat(cleaner.fix("1l5")).isEqualTo("115");
    }

    @Test
    void fullWidthDigits_normalized() {
        assertThat(cleaner.fix("１７０６４９．０８")).isEqualTo("170649.08");
        // 混合：全角数字与半角 O 同串，O 在数字间纠正
        assertThat(cleaner.fix("１７O")).isEqualTo("170");
    }

    @Test
    void forbidden_globalOToZero_notHappened() {
        // 禁止全局 O→0：字母语境一律不动
        assertThat(cleaner.fix("Office")).isEqualTo("Office");
        assertThat(cleaner.fix("GPU")).isEqualTo("GPU");
        assertThat(cleaner.fix("ON OFF")).isEqualTo("ON OFF");
        assertThat(cleaner.fix("Good")).isEqualTo("Good");
        assertThat(cleaner.fix("1050")).isEqualTo("1050");
    }

    @Test
    void iNotCorrected_tooAmbiguous() {
        // I→1 歧义过高：不做
        assertThat(cleaner.fix("1I5")).isEqualTo("1I5");
    }

    @Test
    void wordContext_adjacentToDigits_untouched() {
        // R2 数字间 O 高置信度纠正（夹在数字间即纠正，含字母数字混合 token）；
        // l 无数字前驱（R4 要求数字间）→ 原样；R3 纯数字形 token 才纠首尾 O
        assertThat(cleaner.fix("Room 1O2A")).isEqualTo("Room 102A");
        assertThat(cleaner.fix("l0 方案")).isEqualTo("l0 方案");
    }

    @Test
    void multiToken_structurePreserved() {
        // 多 token 混合：仅数字形 token 纠错，分隔结构（含换行）保留
        assertThat(cleaner.fix("合同工期 17O 天\n面积 O649 平方米\nGood"))
                .isEqualTo("合同工期 170 天\n面积 0649 平方米\nGood");
    }

    @Test
    void cjkText_untouched() {
        // 纯中文不含数字语境 → 原样
        assertThat(cleaner.fix("南地块位于养生大道以南")).isEqualTo("南地块位于养生大道以南");
    }
}
