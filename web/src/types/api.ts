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
 * 雪花 ID 类型：后端统一序列化为字符串，前端禁止使用 number 承接
 * （雪花 ID 超出 JS Number.MAX_SAFE_INTEGER，JSON.parse 即精度丢失）
 */
export type SnowflakeId = string

/**
 * 创建类接口通用返回 VO：后端 Result<IdVO>
 * http 拦截器拆包后即本对象；API 层需取 .id 后再对外返回字符串
 */
export interface IdVO {
    id: SnowflakeId
}

/**
 * 分页查询结果（预留，当前后端未使用）
 */
export interface PageResult<T> {
    records: T[]
    total: number
    current: number
    size: number
}
