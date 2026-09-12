package com.aifp.aiagent.service;

import com.aifp.aiagent.dto.AssistantChatRequest;
import com.aifp.aiagent.dto.AssistantChatResponse;

/**
 * 项目助手服务（需求 §十三/§二十八；Phase 9 会话隔离）
 * <p>
 * 编排流程：守门（权限前置，守门前零 Redis 访问）→ 会话历史读取（降级安全）→
 * QueryIntentAnalyzer（LLM 意图识别 + 指代消解改写）→ UNSUPPORTED 短路 /
 * QueryPlanner（数据源计划）→ 结构化事实 / 项目 RAG / 文件清单 →
 * AssistantPromptBuilder → ChatModel 最终回答 → 组装响应 → 会话记录（后置）。
 * <p>
 * 边界：本层不解析业务数字（只消费 ProjectQueryService 的 normalizedValue，
 * 追加约束 10）、不直接访问 Mapper；会话历史仅用于指代消解，项目事实每轮
 * 必须重新从 ProjectQueryService/ProjectRetrievalService 拉取（Phase 9 追加约束 1）；
 * 会话隔离键 assistant:{projectId}:{conversationId}（§三十三）。
 *
 * @author Tang_tzb
 */
public interface ProjectAssistantService {

    /**
     * 单项目问答（Phase 9 会话隔离：conversationId 空白自动生成；同一
     * conversationId 跨 projectId 为不同会话，历史注入最近 N 轮用于指代消解）。
     *
     * @param projectId 项目ID（权限/存在性守门在 Service 内部完成）
     * @param request   对话请求（message 非空）
     * @return 回答 + 证据集合 + 注入事实
     */
    AssistantChatResponse chat(Long projectId, AssistantChatRequest request);
}
