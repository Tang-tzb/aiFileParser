package com.aifp.aiagent.dto;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

/**
 * 项目助手对话响应（需求 §十三 + §三十六基础版）
 * <p>
 * 引用语义（追加约束 3）：{@link #references} 表示"本次回答可使用的证据集合"
 * （本次注入 LLM 的全部结构化来源 + 全部 RAG 命中），<b>不代表模型实际引用了
 * 每一条</b>；精确 citation（LLM 实际引用筛选）属 Phase 11。
 * <p>
 * 组装边界（追加约束 12）：answer 为纯自然语言；references/structuredData/
 * usedFiles 均由后端代码从 Context 组装，不经 LLM 生成。
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
     * 本次回答可使用的证据集合（结构化来源 + RAG 命中，非"实际引用"筛选，Phase 11 精修）
     */
    private List<AssistantReferenceVO> references = new ArrayList<>();

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
}
