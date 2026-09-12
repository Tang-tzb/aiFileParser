package com.aifp.aiagent.service;

import com.aifp.aiagent.dto.AssistantChatRequest;
import com.aifp.aiagent.dto.AssistantChatResponse;

/**
 * 项目助手服务（需求 §十三/§二十八）
 * <p>
 * 编排流程：守门 → QueryIntentAnalyzer（LLM 意图识别）→ UNSUPPORTED 短路 /
 * QueryPlanner（数据源计划）→ 结构化事实 / 项目 RAG / 文件清单 →
 * AssistantPromptBuilder → ChatModel 最终回答 → 组装响应。
 * <p>
 * 边界：本层不解析业务数字（只消费 ProjectQueryService 的 normalizedValue，
 * 追加约束 10）、不读 ChatMemory（Phase 8 无状态，追加约束 8）、不直接访问 Mapper。
 *
 * @author Tang_tzb
 */
public interface ProjectAssistantService {

    /**
     * 单项目问答（Phase 8 无状态：conversationId 透传/自动生成，不注入历史）。
     *
     * @param projectId 项目ID（权限/存在性守门在 Service 内部完成）
     * @param request   对话请求（message 非空）
     * @return 回答 + 证据集合 + 注入事实
     */
    AssistantChatResponse chat(Long projectId, AssistantChatRequest request);
}
