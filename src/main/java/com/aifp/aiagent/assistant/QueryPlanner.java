package com.aifp.aiagent.assistant;

import org.springframework.stereotype.Component;

/**
 * 意图 → 数据源获取计划映射器（需求 §二十八 QueryPlanner，Phase 8）
 * <p>
 * <b>纯函数</b>：无任何数据访问，仅按 §十七 数据源选择策略做确定性映射，
 * 执行由 ProjectAssistantServiceImpl 完成（职责分离便于单测）。
 * <p>
 * UNSUPPORTED 不进入本方法——编排层在意图识别后立即短路返回确定性文案
 * （追加约束 5：禁止先拉数据再决定"不支持"），传入即视为编程错误。
 * <p>
 * 演进方向（追加约束 2）：未来 QueryIntentAnalyzer 输出 fieldCode/form 槽位后，
 * 本类增加 {@code buildPlan(intent, fieldCode)} 重载，配合 Phase 7
 * ProjectQueryService 字段级精查扩展点，把"全量事实"收窄为"相关事实"；
 * 当前阶段不提前实现，仅保持结构可演进。
 *
 * @author Tang_tzb
 */
@Component
public class QueryPlanner {

    /**
     * 按意图构建数据源获取计划。
     *
     * @param intent 用户问题意图（不可为 null；UNSUPPORTED 非法）
     * @return 数据源获取计划
     */
    public AssistantQueryPlan buildPlan(AssistantIntent intent) {
        return switch (intent) {
            case STRUCTURED -> AssistantQueryPlan.ofStructured();
            case DOCUMENT -> AssistantQueryPlan.ofDocument();
            case HYBRID -> AssistantQueryPlan.ofHybrid();
            case FILE_LIST -> AssistantQueryPlan.ofFileList();
            // UNKNOWN 保守降级为 HYBRID（§十六），Prompt 侧要求不得编造项目事实
            case UNKNOWN -> AssistantQueryPlan.ofUnknown();
            case UNSUPPORTED -> throw new IllegalArgumentException(
                    "UNSUPPORTED 意图须由编排层短路，不进入数据源计划");
        };
    }
}
