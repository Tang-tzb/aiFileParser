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
 * 任务状态中文标签映射
 */
export const TASK_STATUS_LABELS: Record<TaskStatus, string> = {
    [TaskStatus.PARSING]: '解析中',
    [TaskStatus.VECTORING]: '向量化中',
    [TaskStatus.EXTRACTING]: 'AI抽取中',
    [TaskStatus.SUCCESS]: '完成',
    [TaskStatus.FAILED]: '处理失败'
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
 * - fileId/formId 用 number | string 兼容字符串 ID（避免大整数精度丢失）
 */
export interface TaskStartRequest {
    formId: number | string
    fileId: number | string
    /** 项目 ID（Phase 2 起必传，后端校验表单/文件/项目关联一致性，失败返回 6011） */
    projectId: number | string
}

/**
 * 任务启动响应
 * - fileId/formId 用 number | string 兼容后端字符串输出
 */
export interface TaskStartVO {
    taskId: string
    fileId: number | string
    formId: number | string
    createTime: string
}

/**
 * SSE 推送的任务进度
 * - fileId/formId 用 number | string 兼容后端字符串输出
 */
export interface TaskProgress {
    taskId: string
    fileId: number | string
    formId: number | string
    status: TaskStatus
    percent: number
    message: string
    result?: ExtractionResult
    timestamp: number
}
