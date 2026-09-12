import type {ChatMessage} from '@/types/assistant'

/**
 * 助手会话隔离与本地持久化（按项目隔离，硬性规则）
 *
 * - conversationId 存储 key：aifp_assistant_conversation_${projectId}
 * - 后端会话隔离键为 assistant:{projectId}:{conversationId}，前端必须同构按项目隔离；
 *   禁止使用全局 key（如 aifp_conversation），否则切项目后指代类问题会读到上一项目历史
 * - 消息列表本地持久化（后端 Phase 9 才提供会话历史接口）：保持 Tab 切换 / 页面刷新后
 *   上下文不丢；仅保留最近 50 条，Phase 9 接口就绪后可切换为服务端拉取
 */

const MAX_PERSISTED_MESSAGES = 50

const conversationKey = (projectId: string) => `aifp_assistant_conversation_${projectId}`
const messagesKey = (projectId: string) => `aifp_assistant_messages_${projectId}`

/** 读取会话 ID（无则返回 null；首轮请求不携带，响应回来后存入） */
export function loadConversationId(projectId: string): string | null {
    return localStorage.getItem(conversationKey(projectId))
}

/** 保存会话 ID（响应返回的 conversationId） */
export function saveConversationId(projectId: string, conversationId: string): void {
    localStorage.setItem(conversationKey(projectId), conversationId)
}

/** 清除会话 ID（"新会话"时调用，下轮由服务端发新 UUID） */
export function clearConversationId(projectId: string): void {
    localStorage.removeItem(conversationKey(projectId))
}

/** 读取本地消息列表（反序列化失败返回 []） */
export function loadMessages(projectId: string): ChatMessage[] {
    const raw = localStorage.getItem(messagesKey(projectId))
    if (!raw) return []
    try {
        const parsed = JSON.parse(raw)
        return Array.isArray(parsed) ? parsed as ChatMessage[] : []
    } catch {
        return []
    }
}

/** 保存消息列表（剥离 loading 占位，仅保留最近 50 条） */
export function saveMessages(projectId: string, messages: ChatMessage[]): void {
    const persistable = messages
        .filter((m) => !m.loading)
        .slice(-MAX_PERSISTED_MESSAGES)
    localStorage.setItem(messagesKey(projectId), JSON.stringify(persistable))
}

/** 清除本地消息列表（"新会话"时调用） */
export function clearMessages(projectId: string): void {
    localStorage.removeItem(messagesKey(projectId))
}
