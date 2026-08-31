import {type TaskProgress, TaskStatus} from '@/types/task'

/**
 * SSE 任务进度订阅封装（基于浏览器原生 EventSource）
 *
 * - URL 走 vite 代理 /api → /aifp，无需手动拼后端地址
 * - 监听后端事件名 "progress"（SseEmitter.event().name("progress")）
 * - 终态（SUCCESS/FAILED）或异常时自动 close，避免无限重连
 *
 * 用法：
 *   const es = subscribeTaskProgress(taskId, { onProgress, onError })
 *   // 离开页面时 es.close() 主动清理
 */

export interface SseHandlers {
    /** 收到一帧进度 */
    onProgress: (progress: TaskProgress) => void
    /** 连接异常（已自动关闭） */
    onError?: (err: Event) => void
    /** 连接建立 */
    onOpen?: () => void
}

/**
 * 判断是否终态帧（完成或失败）
 */
function isTerminal(p: TaskProgress): boolean {
    return p.status === TaskStatus.SUCCESS
        || p.status === TaskStatus.FAILED
        || p.percent === 100
        || p.percent === -1
}

/**
 * 订阅指定任务的 SSE 进度。
 *
 * @param taskId 任务 ID
 * @param handlers 回调集合
 * @returns EventSource 实例（供外部主动 close）
 */
export function subscribeTaskProgress(taskId: string, handlers: SseHandlers): EventSource {
    const url = `/api/task/progress/${taskId}`
    const es = new EventSource(url)

    es.onopen = () => {
        handlers.onOpen?.()
    }

    es.addEventListener('progress', (e: MessageEvent) => {
        try {
            const data = JSON.parse(e.data) as TaskProgress
            handlers.onProgress(data)
            // 终态自动关闭连接
            if (isTerminal(data)) {
                es.close()
            }
        } catch {
            // JSON 解析失败：忽略本帧，保持连接等待后续帧
        }
    })

    es.onerror = (err) => {
        // 错误时主动关闭，避免 EventSource 默认无限重连
        es.close()
        handlers.onError?.(err)
    }

    return es
}
