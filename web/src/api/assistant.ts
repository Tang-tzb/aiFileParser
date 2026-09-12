import {ElMessage} from 'element-plus'
import type {SnowflakeId} from '@/types/api'
import type {
    AssistantChatRequest,
    AssistantChatResponse,
    AssistantStreamDeltaEvent,
    AssistantStreamErrorEvent
} from '@/types/assistant'

/**
 * 项目助手模块 API（Phase K 流式输出）
 * - 对接后端 ProjectAssistantController：POST /project/{projectId}/assistant/chat
 *   返回 text/event-stream（SSE）
 * - axios 不支持流式响应读取，此处用 fetch + ReadableStream 手动解析 SSE 帧
 * - 事件协议：delta（{"delta":"..."}）→ final（AssistantChatResponse）/ error（{"message":"..."}）
 * - 守门失败（403/6001）与参数错误发生在 SSE 连接建立前：后端返回统一 Result JSON
 */

/** 流式请求总超时（毫秒）：与后端 SseEmitter timeout 120s 对齐 */
const STREAM_TIMEOUT_MS = 120_000

/** 流式回调集合（按事件类型分发） */
export interface AssistantStreamCallbacks {
    /** 回答文本增量（delta 事件，每块调用一次） */
    onDelta?: (delta: string) => void
    /** 完整响应（final 事件，含 references/citations/structuredData/comparisonData） */
    onFinal?: (response: AssistantChatResponse) => void
    /** 编排失败（error 事件；回调后 chatStream 仍会 reject，调用方可统一在 catch 收尾） */
    onError?: (message: string) => void
}

/**
 * 项目助手流式对话
 * - final 事件后 resolve；error 事件/HTTP JSON 错误/连接中断均 reject（Error.message
 *   为用户可读文案，网络异常类错误由调用方兜底提示）
 * - externalSignal：调用方中断（组件卸载等）；与内部 120s 超时合并生效
 */
export async function chatStream(
    projectId: SnowflakeId,
    data: AssistantChatRequest,
    callbacks: AssistantStreamCallbacks = {},
    externalSignal?: AbortSignal
): Promise<void> {
    const controller = new AbortController()
    const timer = setTimeout(
        () => controller.abort(new DOMException('请求超时', 'TimeoutError')),
        STREAM_TIMEOUT_MS
    )
    const onExternalAbort = () => controller.abort(externalSignal?.reason)
    if (externalSignal) {
        externalSignal.addEventListener('abort', onExternalAbort)
    }
    try {
        const res = await fetch(`/api/project/${projectId}/assistant/chat`, {
            method: 'POST',
            headers: {'Content-Type': 'application/json', Accept: 'text/event-stream'},
            body: JSON.stringify(data),
            signal: controller.signal
        })

        // 非 200 或非事件流：按统一 Result JSON 处理（守门失败/参数错误走这里）
        const contentType = res.headers.get('content-type') ?? ''
        if (!res.ok || !contentType.includes('text/event-stream')) {
            await rejectWithResultError(res)
        }

        await consumeSse(res, callbacks)
    } finally {
        clearTimeout(timer)
        if (externalSignal) {
            externalSignal.removeEventListener('abort', onExternalAbort)
        }
    }
}

/**
 * 解析统一 Result JSON 错误并抛出（Error 附加业务码，语义与 request.ts 拦截器一致；
 * 非_Result 结构（网关 502 HTML 等）保留 HTTP 状态兜底文案）
 */
async function rejectWithResultError(res: Response): Promise<never> {
    let code: number | undefined
    let message = `请求失败（HTTP ${res.status}）`
    try {
        const result = await res.json()
        if (result && typeof result === 'object' && result.code !== undefined) {
            code = result.code as number
            message = (result.message as string) || message
        }
    } catch {
        // 非 JSON 响应保持默认文案
    }
    ElMessage.error(message)
    const err: Error & { code?: number } = new Error(message)
    err.code = code
    throw err
}

/**
 * 消费 SSE 字节流：按空行（\n\n）分帧，逐帧解析 event/data 行并分发回调。
 * final/error 事件后置完成标记；流意外终止（无 final/error）视为连接中断。
 */
async function consumeSse(res: Response, callbacks: AssistantStreamCallbacks): Promise<void> {
    const reader = res.body!.getReader()
    const decoder = new TextDecoder('utf-8')
    let buffer = ''
    let finished = false

    /** 解析单个 SSE 帧并分发（data 多行换行合并） */
    const dispatch = (frame: string) => {
        let eventName = 'message'
        const dataLines: string[] = []
        for (const rawLine of frame.split('\n')) {
            const line = rawLine.replace(/\r$/, '')
            if (line.startsWith('event:')) {
                eventName = line.slice(6).trim()
            } else if (line.startsWith('data:')) {
                dataLines.push(line.slice(5).replace(/^ /, ''))
            }
        }
        if (!dataLines.length) return
        const dataText = dataLines.join('\n')

        if (eventName === 'delta') {
            const payload = JSON.parse(dataText) as AssistantStreamDeltaEvent
            if (payload.delta) callbacks.onDelta?.(payload.delta)
        } else if (eventName === 'final') {
            finished = true
            callbacks.onFinal?.(JSON.parse(dataText) as AssistantChatResponse)
        } else if (eventName === 'error') {
            finished = true
            const payload = JSON.parse(dataText) as AssistantStreamErrorEvent
            const message = payload.message || '回答生成失败，请稍后重试'
            callbacks.onError?.(message)
            throw new Error(message)
        }
        // 其他事件名忽略（协议向前兼容）
    }

    try {
        for (; ;) {
            const {done, value} = await reader.read()
            if (done) break
            buffer += decoder.decode(value, {stream: true})
            let sep: number
            while ((sep = buffer.indexOf('\n\n')) >= 0) {
                const frame = buffer.slice(0, sep)
                buffer = buffer.slice(sep + 2)
                if (frame.trim()) dispatch(frame)
            }
        }
    } finally {
        reader.releaseLock()
    }

    if (!finished) {
        throw new Error('连接中断，请重试')
    }
}
