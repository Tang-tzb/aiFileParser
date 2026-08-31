import {post} from './http'
import type {TaskStartRequest, TaskStartVO} from '@/types/task'

/**
 * 任务模块 API
 * - 对接后端 TaskController
 * - 具体 UI 调用在 Phase 4 实现
 * - SSE 进度接口在 Phase 5 实现（见 utils/sse.ts）
 */

// 启动异步解析任务
export function startTask(data: TaskStartRequest) {
    return post<TaskStartVO>('/task', data)
}
