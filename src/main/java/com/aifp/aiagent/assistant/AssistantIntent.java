package com.aifp.aiagent.assistant;

import lombok.AllArgsConstructor;
import lombok.Getter;

import java.util.Arrays;

/**
 * 项目助手用户问题意图枚举（需求 §十四/§十六，Phase 8 版）
 * <p>
 * Phase 8 范围：STRUCTURED / DOCUMENT / HYBRID / FILE_LIST / UNKNOWN 全部可执行；
 * UNSUPPORTED（跨项目比较/排序/统计/筛选/归因分析）属 Phase 10 范围，本阶段
 * 由编排层在意图识别后立即短路返回确定性文案（追加约束 5/14，零检索零 LLM）。
 * <p>
 * {@link #description} 直接拼入意图识别 Prompt 作为判定标准（QueryIntentAnalyzer），
 * 修改判定语义需同步评估 Prompt 效果。
 *
 * @author Tang_tzb
 */
@Getter
@AllArgsConstructor
public enum AssistantIntent {

    /**
     * 单项目字段事实类问题（金额/面积/日期/数量等，答案应来自结构化字段）
     */
    STRUCTURED("单项目字段事实类问题，如询问金额、面积、日期、数量等字段值，答案应来自项目结构化字段"),

    /**
     * 文档内容类问题（原文表述/哪个文档提到什么）
     */
    DOCUMENT("文档内容类问题，如询问某文档原文如何表述、哪个文档提到了某内容"),

    /**
     * 需结合结构化字段与文档内容才能完整回答
     */
    HYBRID("需要同时结合项目结构化字段和文档内容才能完整回答的问题"),

    /**
     * 询问项目有哪些文件/资料/文档清单
     */
    FILE_LIST("询问当前项目有哪些文件、资料或文档清单"),

    /**
     * 跨项目比较/排序/统计/筛选/归因分析——Phase 10 范围，本阶段不支持
     */
    UNSUPPORTED("涉及项目之间比较、排序、统计、筛选或归因分析的问题（如多个项目谁更大、排名、求和、原因分析）"),

    /**
     * 寒暄、与当前项目无关、或无法归类的问题（保守分类，不得编造项目事实）
     */
    UNKNOWN("寒暄、与当前项目无关、或无法明确归入以上任何意图的问题");

    /**
     * 意图判定标准描述（供意图识别 Prompt 拼接）
     */
    private final String description;

    /**
     * 按枚举名解析（LLM 输出的 intent 值）。
     *
     * @param code 枚举名字符串，可空
     * @return 匹配的意图；null/未匹配返回 null（调用方降级 UNKNOWN，追加约束 11）
     */
    public static AssistantIntent parse(String code) {
        if (code == null || code.isBlank()) {
            return null;
        }
        return Arrays.stream(values())
                .filter(intent -> intent.name().equals(code))
                .findFirst()
                .orElse(null);
    }
}
