package com.aifp.aiagent.assistant;

import lombok.Getter;

/**
 * 项目助手数据源获取计划（需求 §二十八 QueryPlanner 产物，Phase 8）
 * <p>
 * 不可变载体：意图 → 需要拉取的数据源组合（§十七 数据源选择策略）。
 * 由 {@link QueryPlanner} 纯函数构建，ProjectAssistantServiceImpl 按计划执行，
 * 两者职责分离便于单测与未来扩展。
 * <p>
 * 演进方向（追加约束 2）：当前 Phase 8 结构化事实为项目级全量注入
 * （{@code queryProjectFacts}）；未来 QueryIntentAnalyzer/QueryPlanner 识别出
 * fieldCode/form 后，可扩展字段级精查（Phase 7 ProjectQueryService 保留扩展点），
 * 仅注入相关事实——本类布尔位可平滑演进为字段级数据源描述，Assistant 不写死全量事实。
 *
 * @author Tang_tzb
 */
@Getter
public final class AssistantQueryPlan {

    private final AssistantIntent intent;

    /**
     * 是否拉取项目结构化事实（ProjectQueryService.queryProjectFacts）
     */
    private final boolean fetchFacts;

    /**
     * 是否执行项目范围 RAG 检索（ProjectRetrievalService.retrieve，query = 用户问题原文）
     */
    private final boolean fetchRag;

    /**
     * 是否拉取项目文件清单（ProjectService.listProjectFiles）
     */
    private final boolean fetchFiles;

    private AssistantQueryPlan(AssistantIntent intent, boolean fetchFacts,
                               boolean fetchRag, boolean fetchFiles) {
        this.intent = intent;
        this.fetchFacts = fetchFacts;
        this.fetchRag = fetchRag;
        this.fetchFiles = fetchFiles;
    }

    /**
     * STRUCTURED：事实 + RAG（追加约束 2：RAG 恒为兜底，不判 forms 空；
     * Prompt 中结构化事实优先于文档片段）
     */
    public static AssistantQueryPlan ofStructured() {
        return new AssistantQueryPlan(AssistantIntent.STRUCTURED, true, true, false);
    }

    /**
     * DOCUMENT：仅项目范围 RAG（§十七 #2）
     */
    public static AssistantQueryPlan ofDocument() {
        return new AssistantQueryPlan(AssistantIntent.DOCUMENT, false, true, false);
    }

    /**
     * HYBRID：事实 + RAG
     */
    public static AssistantQueryPlan ofHybrid() {
        return new AssistantQueryPlan(AssistantIntent.HYBRID, true, true, false);
    }

    /**
     * FILE_LIST：仅文件清单（结构化文件记录，非向量检索）
     */
    public static AssistantQueryPlan ofFileList() {
        return new AssistantQueryPlan(AssistantIntent.FILE_LIST, false, false, true);
    }

    /**
     * UNKNOWN：降级为 HYBRID（§十六"转普通 RAG"；保守兜底，Prompt 要求不得编造）
     */
    public static AssistantQueryPlan ofUnknown() {
        return new AssistantQueryPlan(AssistantIntent.UNKNOWN, true, true, false);
    }

    /**
     * COMPARISON（Phase 10）：跨项目 RAG 兜底 + 字段级结构化数据。
     * 单项目全量 facts 关闭（fetchFacts=false）——比较结构化数据由
     * ProjectComparisonService 按 (projectId, projectFormId, fieldCode) 事实单元
     * 字段级拉取（追加约束 4），兑现"布尔位可平滑演进为字段级数据源描述"的演进承诺；
     * RAG 仅作解释依据，数字结论只能来自 FieldComparisonCalculator（追加约束 11）。
     */
    public static AssistantQueryPlan ofComparison() {
        return new AssistantQueryPlan(AssistantIntent.COMPARISON, false, true, false);
    }
}
