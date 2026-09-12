package com.aifp.aiagent.assistant;

import lombok.Getter;

/**
 * 意图识别结果（Phase 9 契约升级：意图 + 改写后独立问题）
 * <p>
 * {@link #standaloneQuestion} 是本轮<b>唯一有效问题</b>（用户追加约束 2）：
 * QueryPlanner/RAG 与 AssistantPromptBuilder 的用户问题均使用它；
 * 原始 message 仅用于历史记录与 conversation 语义。
 * 改写仅做指代消解（"那面积呢？"→"这个项目的建筑面积是多少？"），
 * 无历史或问题已独立时与原始问题相同（改写失败自动退化为 Phase 8 行为）。
 *
 * @author Tang_tzb
 */
@Getter
public class AssistantIntentAnalysis {

    private final AssistantIntent intent;

    /**
     * 改写后的独立问题（指代已消解；解析失败时兜底为原始问题）
     */
    private final String standaloneQuestion;

    public AssistantIntentAnalysis(AssistantIntent intent, String standaloneQuestion) {
        this.intent = intent;
        this.standaloneQuestion = standaloneQuestion;
    }

    /**
     * 非法输出降级结果：UNKNOWN + 原始问题原样返回（不编造改写）。
     */
    public static AssistantIntentAnalysis fallback(String originalQuestion) {
        return new AssistantIntentAnalysis(AssistantIntent.UNKNOWN, originalQuestion);
    }
}
