package com.aifp.aiagent.assistant;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link QueryPlanner} 测试（离线纯函数，Phase 8 T2）
 * <p>
 * 覆盖 §十七 数据源映射：五意图 × fetch 布尔组合精确断言；
 * UNSUPPORTED 不进入计划（编排层 Service 短路保证，追加约束 5）。
 *
 * @author Tang_tzb
 */
class QueryPlannerTest {

    private final QueryPlanner planner = new QueryPlanner();

    /**
     * STRUCTURED → 事实 + RAG 恒兜底（追加约束 2：不判 forms 空，无条件分支），
     * 不拉文件清单
     */
    @Test
    void structured_factsAndRagWithoutFiles() {
        AssistantQueryPlan plan = planner.buildPlan(AssistantIntent.STRUCTURED);

        assertThat(plan.getIntent()).isEqualTo(AssistantIntent.STRUCTURED);
        assertThat(plan.isFetchFacts()).isTrue();
        assertThat(plan.isFetchRag()).isTrue();
        assertThat(plan.isFetchFiles()).isFalse();
    }

    /**
     * DOCUMENT → 仅 RAG
     */
    @Test
    void document_ragOnly() {
        AssistantQueryPlan plan = planner.buildPlan(AssistantIntent.DOCUMENT);

        assertThat(plan.isFetchFacts()).isFalse();
        assertThat(plan.isFetchRag()).isTrue();
        assertThat(plan.isFetchFiles()).isFalse();
    }

    /**
     * HYBRID → 事实 + RAG
     */
    @Test
    void hybrid_factsAndRag() {
        AssistantQueryPlan plan = planner.buildPlan(AssistantIntent.HYBRID);

        assertThat(plan.isFetchFacts()).isTrue();
        assertThat(plan.isFetchRag()).isTrue();
        assertThat(plan.isFetchFiles()).isFalse();
    }

    /**
     * FILE_LIST → 仅文件清单
     */
    @Test
    void fileList_filesOnly() {
        AssistantQueryPlan plan = planner.buildPlan(AssistantIntent.FILE_LIST);

        assertThat(plan.isFetchFacts()).isFalse();
        assertThat(plan.isFetchRag()).isFalse();
        assertThat(plan.isFetchFiles()).isTrue();
    }

    /**
     * UNKNOWN → 保守降级为事实 + RAG（§十六：不编造项目事实，
     * 与项目明显无关由 Prompt 侧回答"不属于当前项目资料范围"）
     */
    @Test
    void unknown_factsAndRag() {
        AssistantQueryPlan plan = planner.buildPlan(AssistantIntent.UNKNOWN);

        assertThat(plan.getIntent()).isEqualTo(AssistantIntent.UNKNOWN);
        assertThat(plan.isFetchFacts()).isTrue();
        assertThat(plan.isFetchRag()).isTrue();
        assertThat(plan.isFetchFiles()).isFalse();
    }

    /**
     * UNSUPPORTED 非法入计划：必须由编排层在意图识别后立即短路（追加约束 5），
     * 进入计划即编码缺陷，快速失败
     */
    @Test
    void unsupported_rejected() {
        assertThatThrownBy(() -> planner.buildPlan(AssistantIntent.UNSUPPORTED))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
