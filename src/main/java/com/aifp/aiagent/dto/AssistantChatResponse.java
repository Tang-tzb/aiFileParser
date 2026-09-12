package com.aifp.aiagent.dto;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

/**
 * 项目助手对话响应（需求 §十三 + §三十六基础版；Phase 11 增加引用溯源）
 * <p>
 * 引用语义（追加约束 3）：{@link #references} 表示"本次回答可使用的证据集合"
 * （本次注入 LLM 的全部结构化来源 + 全部 RAG 命中），<b>不代表模型实际引用了
 * 每一条</b>；{@link #citations} 为 LLM 实际引用子集（answer 中的引用标记经
 * AnswerCitationParser 确定性解析映射回 references 成员）。
 * <p>
 * 组装边界（追加约束 12）：answer 为纯自然语言 + 引用标记（标记保留不剥离，
 * 前端可渲染上标/来源卡片，后端保留原始回答供审计）；references/citations/
 * structuredData/usedFiles 均由后端代码组装，不经 LLM 生成。
 *
 * @author Tang_tzb
 */
@Data
public class AssistantChatResponse implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 会话 ID（请求透传或服务端生成的 UUID）
     */
    private String conversationId;

    /**
     * 回答文本（纯自然语言）
     */
    private String answer;

    /**
     * 本次回答可使用的证据集合（结构化来源 + RAG 命中，非"实际引用"筛选）；
     * 顺序即 Prompt 渲染顺序，成员携带 citationId 与 Prompt 标记一一对应
     */
    private List<AssistantReferenceVO> references = new ArrayList<>();

    /**
     * LLM 实际引用的证据子集（Phase 11）：answer 中出现的 [S{n}]/[D{n}] 标记经
     * AnswerCitationParser 确定性解析映射回 {@link #references} 成员（首次出现
     * 顺序去重，共享同一 VO 实例）；解析只降级不报错——未知标记仅 warn 忽略，
     * 不产生 references 之外的新证据项
     */
    private List<AssistantReferenceVO> citations = new ArrayList<>();

    /**
     * 本次注入 LLM 的字段事实（前端可渲染冲突卡片；未拉取事实时为空列表）
     */
    private List<ProjectStructuredFactsVO.Field> structuredData = new ArrayList<>();

    /**
     * 涉及的项目 ID（本阶段恒为单元素 [projectId]）
     */
    @JsonSerialize(contentUsing = ToStringSerializer.class)
    private List<Long> usedProjects = new ArrayList<>();

    /**
     * 涉及的文件 ID（结构化来源文件 ∪ RAG 命中文件去重；文件清单场景不贡献）
     */
    @JsonSerialize(contentUsing = ToStringSerializer.class)
    private List<Long> usedFiles = new ArrayList<>();

    /**
     * 跨项目比较数据（Phase 10，仅 COMPARISON 意图填充）：后端确定性计算的
     * 排名/聚合/差值与排除明细；其余意图为 null
     */
    private CrossProjectComparisonVO comparisonData;
}
