package com.aifp.aiagent.assistant;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 项目助手会话历史存储（需求 §三十二/§三十三，Phase 9）
 * <p>
 * Redis List 存储一问一答轮次，key 强制 {@code assistant:{projectId}:{conversationId}}
 * （§三十三 + 硬约束 ⑨）：同一 conversationId 跨 projectId 必然隔离，
 * <b>不提供仅凭 conversationId 读取历史的方法</b>（追加约束 6，防伪造跨项目访问）。
 * <p>
 * 降级语义（追加约束 4）：会话历史属辅助记忆非业务事实——Redis 读写失败仅 warn，
 * 不抛异常、不阻断已成功的回答；load 降级为无历史（回答链路退化为 Phase 8 行为）。
 * <p>
 * 双重限长（追加约束 3）：{@code max-stored-turns} 控制 Redis 数据长度（trim 保留最近 N 轮），
 * 注入 Prompt 的数量由调用方 maxTurns 参数（max-injected-turns）独立控制。
 * List 顺序即时间顺序：rightPush 追加于尾部，最近一轮在最后。
 *
 * @author Tang_tzb
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ConversationHistoryStore {

    /**
     * 隔离键模式（§三十三）：projectId 纳入会话身份，杜绝跨项目上下文污染
     */
    private static final String KEY_PATTERN = "assistant:%d:%s";

    private final RedisTemplate<String, Object> redisTemplate;

    /**
     * 会话历史 TTL（小时）：每次 append 后刷新（追加约束 7），
     * 代表"最近活跃会话"的存活时间而非首次创建后的固定时长
     */
    @Value("${assistant.conversation.history-ttl-hours:24}")
    private long historyTtlHours;

    /**
     * Redis 中最多保留轮次（追加约束 3：存储侧限长）
     */
    @Value("${assistant.conversation.max-stored-turns:20}")
    private int maxStoredTurns;

    /**
     * 读取最近 maxTurns 轮历史（时间顺序，最近一轮在最后）。
     * <p>
     * 必须在权限守门（ProjectService 403/6001）之后调用，未授权项目零 Redis 访问。
     *
     * @param projectId      项目 ID（隔离键组成部分，必填）
     * @param conversationId 会话 ID（隔离键组成部分，必填）
     * @param maxTurns       注入上限（max-injected-turns，与存储侧限长相互独立）
     * @return 最近轮次列表；Redis 异常/无数据时返回空列表（降级无历史，不抛异常）
     */
    public List<ConversationTurn> loadRecentTurns(Long projectId, String conversationId, int maxTurns) {
        String key = buildKey(projectId, conversationId);
        try {
            List<Object> raw = redisTemplate.opsForList().range(key, -maxTurns, -1);
            if (raw == null) {
                return List.of();
            }
            return raw.stream()
                    .filter(ConversationTurn.class::isInstance)
                    .map(ConversationTurn.class::cast)
                    .toList();
        } catch (DataAccessException e) {
            log.warn("会话历史读取失败，降级无历史 projectId={}, conversationId={}: {}",
                    projectId, conversationId, e.getMessage());
            return List.of();
        }
    }

    /**
     * 追加一轮对话（辅助记忆，绝不改变已生成的回答——追加约束 4）。
     * <p>
     * 写入序：rightPush（尾部追加，保持时间顺序）→ expire（每次 append 后刷新 TTL，
     * 追加约束 7）→ trim（保留最近 max-stored-turns 轮，追加约束 3）；
     * Redis 异常仅 warn 不抛。
     *
     * @param projectId       项目 ID（隔离键组成部分）
     * @param conversationId  会话 ID（隔离键组成部分）
     * @param userQuestion    用户问题原文（非改写值，忠实记录）
     * @param assistantAnswer 实际返回给用户的回答（含 UNSUPPORTED 固定文案）
     */
    public void appendTurn(Long projectId, String conversationId,
                           String userQuestion, String assistantAnswer) {
        String key = buildKey(projectId, conversationId);
        try {
            redisTemplate.opsForList().rightPush(key, ConversationTurn.builder()
                    .userQuestion(userQuestion)
                    .assistantAnswer(assistantAnswer)
                    .createTime(LocalDateTime.now())
                    .build());
            redisTemplate.expire(key, Duration.ofHours(historyTtlHours));
            redisTemplate.opsForList().trim(key, -maxStoredTurns, -1);
        } catch (DataAccessException e) {
            log.warn("会话历史写入失败（不影响回答返回）projectId={}, conversationId={}: {}",
                    projectId, conversationId, e.getMessage());
        }
    }

    // ==================== 内部方法 ====================

    /**
     * 构建隔离键：projectId 强制参与（追加约束 6），禁止裸 conversationId 键。
     */
    private String buildKey(Long projectId, String conversationId) {
        return String.format(KEY_PATTERN, projectId, conversationId);
    }
}
