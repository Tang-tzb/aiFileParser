package com.aifp.aiagent.service;

import com.aifp.aiagent.dto.AssistantChatRequest;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * 项目助手服务（需求 §十三/§二十八；Phase 9 会话隔离；Phase K 流式输出）
 * <p>
 * 编排流程：守门（权限前置，守门前零 Redis 访问）→ 建立 SSE 连接（异步线程执行）→
 * 会话历史读取（降级安全）→ QueryIntentAnalyzer（LLM 意图识别 + 指代消解改写）→
 * UNSUPPORTED 短路 / QueryPlanner（数据源计划）→ 结构化事实 / 项目 RAG / 文件清单 →
 * AssistantPromptBuilder → ChatModel 流式最终回答（delta 增量推送）→ 组装响应
 * （final 事件）→ 会话记录（后置）。
 * <p>
 * SSE 事件协议（Phase K）：{@code delta}=回答文本增量（{"delta":"..."}）；
 * {@code final}=完整响应（AssistantChatResponse JSON）；{@code error}=失败消息
 * （{"message":"..."}）。守门失败不建立流，以 HTTP JSON 错误返回。
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
     * 单项目问答（SSE 流式，Phase K）。守门同步完成（403/6001 抛业务异常走 HTTP JSON），
     * 通过后建立 SSE 连接并提交异步编排：意图分析 → 检索 → 流式回答（delta）→
     * final/error 收尾。
     *
     * @param projectId 项目ID（权限/存在性守门在 Service 内部完成）
     * @param request   对话请求（message 非空）
     * @return SseEmitter（timeout 120s；事件 delta/final/error）
     */
    SseEmitter chatStream(Long projectId, AssistantChatRequest request);
}
