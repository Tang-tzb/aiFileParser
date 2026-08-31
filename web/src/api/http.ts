import request from '@/utils/request'
import type {AxiosRequestConfig} from 'axios'

/**
 * 通用 HTTP 请求封装
 * 业务层统一使用这些方法，响应已被拦截器拆解为 Result.data
 * - get/post/put/delete 四个方法
 * - 泛型 T 表示期望的 data 类型
 */

export function get<T = unknown>(url: string, params?: Record<string, unknown>, config?: AxiosRequestConfig) {
    return request.get<unknown, T>(url, {params, ...config})
}

export function post<T = unknown>(url: string, data?: unknown, config?: AxiosRequestConfig) {
    return request.post<unknown, T>(url, data, config)
}

export function put<T = unknown>(url: string, data?: unknown, config?: AxiosRequestConfig) {
    return request.put<unknown, T>(url, data, config)
}

export function del<T = unknown>(url: string, config?: AxiosRequestConfig) {
    return request.delete<unknown, T>(url, config)
}

export default {get, post, put, del}
