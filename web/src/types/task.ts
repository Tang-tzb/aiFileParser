/**
 * 异步任务状态枚举（对应后端 TaskStatus）
 */
export enum TaskStatus {
    PARSING = 'PARSING',
    VECTORING = 'VECTORING',
    EXTRACTING = 'EXTRACTING',
    SUCCESS = 'SUCCESS',
    FAILED = 'FAILED'
}

/**
 * 任务进度百分比常量
 */
export const TASK_PERCENT = {
    PARSING: 0,
    VECTORING: 50,
    EXTRACTING: 80,
    SUCCESS: 100,
    FAILED: -1
} as const

/**
 * AI 字段提取错误
 */
export interface FieldError {
    fieldCode: string
    errorType: string
    message: string
    rawValue: unknown
}

/**
 * AI 提取结果
 */
export interface ExtractionResult {
    values: Record<string, unknown>
    errors: FieldError[]
    attemptsUsed: number
}

/**
 * 任务启动请求
 */
export interface TaskStartRequest {
    formId: number
    fileId: number
}

/**
 * 任务启动响应
 */
export interface TaskStartVO {
    taskId: string
    fileId: number
    formId: number
    createTime: string
}

/**
 * SSE 推送的任务进度
 */
export interface TaskProgress {
    taskId: string
    fileId: number
    formId: number
    status: TaskStatus
    percent: number
    message: string
    result?: ExtractionResult
    timestamp: number
}
