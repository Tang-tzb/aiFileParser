package com.aifp.aiagent.assistant;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.data.redis.core.ListOperations;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * {@link ConversationHistoryStore} 测试（离线，Phase 9 T1）
 * <p>
 * 覆盖：隔离键格式 {@code assistant:{projectId}:{conversationId}}（§三十三 +
 * 追加约束 6，projectId 强制参与防伪造跨项目访问）；双重限长（load 注入侧
 * range 参数 / append 存储侧 trim 参数，追加约束 3）；时间顺序（最近一轮在最后，
 * range 负区间语义）；TTL 每次 append 刷新（追加约束 7）；Redis 异常降级
 * 不抛（追加约束 4：辅助数据语义）。
 *
 * @author Tang_tzb
 */
@ExtendWith(MockitoExtension.class)
@SuppressWarnings("unchecked")
class ConversationHistoryStoreTest {

    private static final Long PROJECT_ID = 1785900001L;
    private static final String CONVERSATION_ID = "conv-001";
    private static final String KEY = "assistant:1785900001:conv-001";

    @Mock
    private RedisTemplate<String, Object> redisTemplate;
    @Mock
    private ListOperations<String, Object> listOperations;

    private ConversationHistoryStore store;

    @BeforeEach
    void setUp() {
        store = new ConversationHistoryStore(redisTemplate);
        ReflectionTestUtils.setField(store, "historyTtlHours", 24L);
        ReflectionTestUtils.setField(store, "maxStoredTurns", 20);
        lenient().when(redisTemplate.opsForList()).thenReturn(listOperations);
    }

    // ==================== 隔离键（追加约束 6） ====================

    /**
     * key 强制含 projectId：同 conversationId 跨 projectId 必然不同键
     */
    @Test
    void load_keyContainsProjectId() {
        when(listOperations.range(anyString(), anyLong(), anyLong())).thenReturn(List.of());

        store.loadRecentTurns(PROJECT_ID, CONVERSATION_ID, 6);

        verify(listOperations).range(eq("assistant:" + PROJECT_ID + ":" + CONVERSATION_ID), anyLong(), anyLong());
    }

    /**
     * append 同样使用含 projectId 的隔离键
     */
    @Test
    void append_keyContainsProjectId() {
        store.appendTurn(PROJECT_ID, CONVERSATION_ID, "问题", "回答");

        verify(listOperations).rightPush(eq(KEY), any());
    }

    // ==================== loadRecentTurns ====================

    /**
     * 读取最近 N 轮：range 负区间（-maxTurns, -1）= 时间顺序最近一轮在最后（追加约束 3）
     */
    @Test
    void load_usesNegativeRangeForRecentTurns() {
        when(listOperations.range(anyString(), anyLong(), anyLong())).thenReturn(List.of());

        store.loadRecentTurns(PROJECT_ID, CONVERSATION_ID, 6);

        verify(listOperations).range(KEY, -6L, -1L);
    }

    /**
     * 命中数据：类型过滤（异构脏数据跳过，不猜测）
     */
    @Test
    void load_filtersNonTurnEntries() {
        ConversationTurn turn = ConversationTurn.builder()
                .userQuestion("q1").assistantAnswer("a1").createTime(LocalDateTime.now())
                .build();
        when(listOperations.range(KEY, -6L, -1L)).thenReturn(List.of(turn, "dirty-entry"));

        List<ConversationTurn> result = store.loadRecentTurns(PROJECT_ID, CONVERSATION_ID, 6);

        assertThat(result).containsExactly(turn);
    }

    /**
     * 无数据/Redis 返回 null → 空列表
     */
    @Test
    void load_emptyReturnsEmptyList() {
        when(listOperations.range(KEY, -6L, -1L)).thenReturn(null);

        assertThat(store.loadRecentTurns(PROJECT_ID, CONVERSATION_ID, 6)).isEmpty();
    }

    /**
     * Redis 读异常 → 降级空列表不抛（追加约束 4：回答链路退化为无历史）
     */
    @Test
    void load_redisFailure_degradesToEmptyList() {
        when(listOperations.range(anyString(), anyLong(), anyLong()))
                .thenThrow(new DataAccessResourceFailureException("redis connection failed"));

        assertThat(store.loadRecentTurns(PROJECT_ID, CONVERSATION_ID, 6)).isEmpty();
    }

    // ==================== appendTurn ====================

    /**
     * 写入序：rightPush（时间顺序尾部追加）→ expire（TTL 刷新，追加约束 7）→
     * trim（存储侧限长，追加约束 3）
     */
    @Test
    void append_pushThenRefreshTtlThenTrim() {
        store.appendTurn(PROJECT_ID, CONVERSATION_ID, "那面积呢？", "建筑面积为100平米。");

        ArgumentCaptor<Object> turnCaptor = ArgumentCaptor.forClass(Object.class);
        verify(listOperations).rightPush(eq(KEY), turnCaptor.capture());
        ConversationTurn saved = (ConversationTurn) turnCaptor.getValue();
        assertThat(saved.getUserQuestion()).isEqualTo("那面积呢？");
        assertThat(saved.getAssistantAnswer()).isEqualTo("建筑面积为100平米。");
        assertThat(saved.getCreateTime()).isNotNull();

        InOrder inOrder = inOrder(redisTemplate, listOperations);
        inOrder.verify(listOperations).rightPush(eq(KEY), any());
        inOrder.verify(redisTemplate).expire(KEY, Duration.ofHours(24));
        inOrder.verify(listOperations).trim(KEY, -20L, -1L);
    }

    /**
     * Redis 写异常 → 仅降级不抛（追加约束 4：绝不影响已生成的回答）
     */
    @Test
    void append_redisFailure_doesNotThrow() {
        when(listOperations.rightPush(anyString(), any()))
                .thenThrow(new DataAccessResourceFailureException("redis connection failed"));

        assertThatCode(() -> store.appendTurn(PROJECT_ID, CONVERSATION_ID, "问题", "回答"))
                .doesNotThrowAnyException();
    }
}
