package com.aifp.aiagent.assistant;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Builder;
import lombok.Getter;
import lombok.extern.jackson.Jacksonized;

import java.time.LocalDateTime;

/**
 * 项目助手对话单轮（一问一答，Redis 辅助数据载体）
 * <p>
 * 仅存储于 Redis（追加约束 8）：不落库、不新增任何业务表字段；
 * 通过 {@code GenericJackson2JsonRedisSerializer}（PTV 白名单含 aiagent 包）round-trip，
 * {@link Jacksonized} 支持基于 builder 的反序列化。
 * <p>
 * 语义边界（追加约束 1）：历史轮次仅用于指代消解（"那/它/还有呢"），
 * 不是项目事实来源——事实每轮必须重新由 ProjectQueryService/ProjectRetrievalService提供。
 *
 * @author Tang_tzb
 */
@Getter
@Builder
@Jacksonized
@JsonIgnoreProperties(ignoreUnknown = true)
public class ConversationTurn {

    /**
     * 用户问题原文（忠实记录对话，非改写后的 standaloneQuestion）
     */
    private final String userQuestion;

    /**
     * 助手回答文本（实际返回给用户的内容，含 UNSUPPORTED 固定文案）
     */
    private final String assistantAnswer;

    /**
     * 轮次时间（Redis 序列化器已注册 JavaTimeModule，ISO-8601 字符串）
     */
    private final LocalDateTime createTime;
}
