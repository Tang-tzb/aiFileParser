package com.aifp.aiagent.entity.enums;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link FileStatus} 状态机穷举测试（阶段 13）
 * <p>
 * 遍历全部 6×6 from×to 组合钉死迁移白名单：表内放行、表外（含终态 SUCCESS
 * 任何出边、同态）一律拒绝。用户点名三条非法跳转以专项用例钉死：
 * SUCCESS → EXTRACTING、SUCCESS → PARSING、VECTORING → PARSING。
 * 同态写（from == to）由 FileServiceImpl 按幂等 no-op 处理，canTransitionTo 一律 false。
 *
 * @author Tang_tzb
 */
class FileStatusTest {

    /**
     * 合法迁移白名单（与 FileStatus 静态表一致，双写防漂移）：
     * UPLOADED→PARSING；PARSING→VECTORING/FAILED；VECTORING→EXTRACTING/FAILED；
     * EXTRACTING→SUCCESS/FAILED；FAILED→PARSING；SUCCESS→（终态封闭）。
     */
    private static final Set<String> LEGAL_PAIRS = Set.of(
            "UPLOADED->PARSING",
            "PARSING->VECTORING", "PARSING->FAILED",
            "VECTORING->EXTRACTING", "VECTORING->FAILED",
            "EXTRACTING->SUCCESS", "EXTRACTING->FAILED",
            "FAILED->PARSING");

    private static String pair(FileStatus from, FileStatus to) {
        return from.name() + "->" + to.name();
    }

    /**
     * 穷举全部 from×to 组合：白名单内必须放行，白名单外必须拒绝（含同态）。
     */
    @Test
    void canTransitionTo_exhaustiveAllCombinations() {
        for (FileStatus from : FileStatus.values()) {
            for (FileStatus to : FileStatus.values()) {
                boolean expected = LEGAL_PAIRS.contains(pair(from, to));
                assertThat(from.canTransitionTo(to))
                        .as("状态迁移 %s → %s 应为 %s", from, to, expected ? "合法" : "非法")
                        .isEqualTo(expected);
            }
        }
    }

    /**
     * 非法跳转专项（阶段 13 修复目标，用户点名）：终态 SUCCESS 任何出边均非法，
     * 已入库状态（VECTORING/EXTRACTING）不得回退 PARSING。
     */
    @Test
    void canTransitionTo_illegalJumps_namedCases() {
        // 阶段 13 核心修复：SUCCESS → EXTRACTING 倒退必须非法
        assertThat(FileStatus.SUCCESS.canTransitionTo(FileStatus.EXTRACTING)).isFalse();
        assertThat(FileStatus.SUCCESS.canTransitionTo(FileStatus.PARSING)).isFalse();
        assertThat(FileStatus.SUCCESS.canTransitionTo(FileStatus.VECTORING)).isFalse();
        assertThat(FileStatus.SUCCESS.canTransitionTo(FileStatus.FAILED)).isFalse();
        // 已入库状态回退 PARSING 必须非法
        assertThat(FileStatus.VECTORING.canTransitionTo(FileStatus.PARSING)).isFalse();
        assertThat(FileStatus.EXTRACTING.canTransitionTo(FileStatus.PARSING)).isFalse();
        assertThat(FileStatus.EXTRACTING.canTransitionTo(FileStatus.VECTORING)).isFalse();
        // FAILED 仅可回 PARSING，不得跳跃到后续阶段或直接成功
        assertThat(FileStatus.FAILED.canTransitionTo(FileStatus.VECTORING)).isFalse();
        assertThat(FileStatus.FAILED.canTransitionTo(FileStatus.EXTRACTING)).isFalse();
        assertThat(FileStatus.FAILED.canTransitionTo(FileStatus.SUCCESS)).isFalse();
        // UPLOADED 不得跳阶段
        assertThat(FileStatus.UPLOADED.canTransitionTo(FileStatus.VECTORING)).isFalse();
        assertThat(FileStatus.UPLOADED.canTransitionTo(FileStatus.EXTRACTING)).isFalse();
        assertThat(FileStatus.UPLOADED.canTransitionTo(FileStatus.SUCCESS)).isFalse();
    }

    /**
     * 合法主链路冒烟：完整正向序列与 FAILED 重试回路逐跳放行。
     */
    @Test
    void canTransitionTo_legalMainChain() {
        assertThat(FileStatus.UPLOADED.canTransitionTo(FileStatus.PARSING)).isTrue();
        assertThat(FileStatus.PARSING.canTransitionTo(FileStatus.VECTORING)).isTrue();
        assertThat(FileStatus.VECTORING.canTransitionTo(FileStatus.EXTRACTING)).isTrue();
        assertThat(FileStatus.EXTRACTING.canTransitionTo(FileStatus.SUCCESS)).isTrue();
        // 失败路径：任一非终态均可落 FAILED；FAILED 重试回 PARSING
        assertThat(FileStatus.PARSING.canTransitionTo(FileStatus.FAILED)).isTrue();
        assertThat(FileStatus.VECTORING.canTransitionTo(FileStatus.FAILED)).isTrue();
        assertThat(FileStatus.EXTRACTING.canTransitionTo(FileStatus.FAILED)).isTrue();
        assertThat(FileStatus.FAILED.canTransitionTo(FileStatus.PARSING)).isTrue();
    }

    /**
     * null 与同态输入防御：一律 false（同态 no-op 由 FileServiceImpl 处理）。
     */
    @Test
    void canTransitionTo_nullAndSameState_rejected() {
        for (FileStatus status : FileStatus.values()) {
            assertThat(status.canTransitionTo(null)).as("null 目标应拒绝").isFalse();
            assertThat(status.canTransitionTo(status)).as("同态应拒绝: " + status).isFalse();
        }
    }

    /**
     * 穷举无遗漏防御：6×6=36 组合恰好覆盖，防白名单表结构意外变更。
     */
    @Test
    void exhaustiveCombinationCount_complete() {
        long combinations = (long) FileStatus.values().length * FileStatus.values().length;
        assertThat(combinations).isEqualTo(36);
        assertThat(Arrays.stream(FileStatus.values()).map(FileStatus::name))
                .containsExactlyInAnyOrder("UPLOADED", "PARSING", "VECTORING",
                        "EXTRACTING", "SUCCESS", "FAILED");
    }
}