/**
 * 后端统一返回结构 Result<T>
 * 对应后端 com.aifp.common.result.Result
 */
export interface Result<T> {
    code: number
    message: string
    data: T
    timestamp: number
}

/**
 * 后端业务成功状态码
 */
export const RESULT_SUCCESS_CODE = 200

/**
 * 分页查询结果（预留，当前后端未使用）
 */
export interface PageResult<T> {
    records: T[]
    total: number
    current: number
    size: number
}
