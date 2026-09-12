package com.aifp.aiagent.assistant;

import com.aifp.aiagent.dto.CrossProjectComparisonVO;
import com.aifp.aiagent.dto.ProjectStructuredFactsVO;
import lombok.Builder;
import lombok.Getter;
import org.springframework.ai.document.Document;

import java.util.List;

/**
 * 项目助手统一上下文（需求 §二十一）
 * <p>
 * 普通数据载体（非 Spring bean）：由 {@code ProjectAssistantServiceImpl} 按
 * {@link AssistantQueryPlan} 拉取结果组装，仅被 {@link AssistantPromptBuilder} 消费，
 * 用于构建第二次 LLM（最终回答）的 Prompt。
 * <p>
 * 事实边界（硬约束 ③/追加约束 1）：回答 LLM 只能依据本对象携带的数据作答，
 * 禁止模型依据自身知识补充项目事实；未拉取的维度对应字段为 null/空集合。
 * <p>
 * 演进预留（追加约束 2）：本 Phase 携带项目级全量事实；未来 QueryPlanner 增加
 * fieldCode/form 维度后，{@code facts} 可收窄为字段级事实，本类结构无需破坏性变更。
 *
 * @author Tang_tzb
 */
@Getter
@Builder
public class ProjectAssistantContext {

    /**
     * 项目名称（仅用于 Prompt 消歧语境，非事实来源，追加约束 13）
     */
    private final String projectName;

    /**
     * 用户问题（Phase 9：standaloneQuestion 改写后独立问题——本轮唯一有效问题，
     * RAG query 与 Prompt 共用；原始 message 仅入对话历史，追加约束 2）
     */
    private final String question;

    /**
     * 对话历史（最近 N 轮，时间顺序最近一轮在最后；首轮为空集合）。
     * 仅用于指代消解（追加约束 1）：历史不是项目事实来源，事实必须来自
     * 本次拉取的 facts/documents
     */
    @Builder.Default
    private final List<ConversationTurn> history = List.of();

    /**
     * 项目结构化事实（未拉取时为 null；拉取后整体注入，含冲突标记）
     */
    private final ProjectStructuredFactsVO facts;

    /**
     * 跨项目比较数据（Phase 10，仅 COMPARISON 意图非 null）：后端
     * FieldComparisonCalculator 确定性计算的排名/聚合/差值与排除明细；
     * LLM 只能引用已算结果，禁止自行计算（追加约束 6/11）
     */
    private final CrossProjectComparisonVO comparison;

    /**
     * 项目范围 RAG 命中的文档片段（未拉取时为空集合）
     */
    @Builder.Default
    private final List<Document> documents = List.of();

    /**
     * 项目文件清单（仅文件名，未拉取时为空集合）
     */
    @Builder.Default
    private final List<String> fileNames = List.of();

    /**
     * 本次是否拉取了结构化事实（响应组装 structuredData 的依据）
     */
    private final boolean factsUsed;

    /**
     * 本次是否执行了 RAG 检索（响应组装 FILE 组 references 的依据）
     */
    private final boolean ragUsed;

    /**
     * 本次是否拉取了文件清单（响应组装 usedFiles 的补充依据）
     */
    private final boolean filesUsed;
}
