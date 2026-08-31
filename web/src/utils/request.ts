import axios, {type AxiosInstance, type AxiosRequestConfig, AxiosError} from 'axios'
import {ElMessage} from 'element-plus'
import {RESULT_SUCCESS_CODE, type Result} from '@/types/api'

/**
 * Axios 实例
 * - baseURL: /api（由 Vite 代理到 http://localhost:8080/aifp）
 * - 统一在响应拦截器中拆解 Result，业务层直接拿到 data
 * - 业务错误（code !== 200）统一 ElMessage 提示，无需在页面重复处理
 */
const service: AxiosInstance = axios.create({
    baseURL: '/api',
    timeout: 30000,
    headers: {
        'Content-Type': 'application/json'
    }
})

// 请求拦截器：预留 token 注入位置
service.interceptors.request.use(
    (config) => {
        // 后续如需鉴权，可在此注入 Authorization
        return config
    },
    (error) => Promise.reject(error)
)

// 响应拦截器：统一处理 Result<T>，剥离外层包装
service.interceptors.response.use(
    (response) => {
        const result = response.data as Result<unknown>

        // 非 Result 结构（如下载流），直接返回
        if (result === null || typeof result !== 'object' || result.code === undefined) {
            return response.data
        }

        // 业务成功：返回 data 部分，供业务层直接使用
        if (result.code === RESULT_SUCCESS_CODE) {
            return result.data
        }

        // 业务失败：统一提示，返回 rejected promise
        ElMessage.error(result.message || '请求失败')
        return Promise.reject(new Error(result.message || '请求失败'))
    },
    (error: AxiosError) => {
        // HTTP 层错误统一处理
        const status = error.response?.status
        let message = '网络异常，请稍后重试'

        if (status === 404) {
            message = '请求资源不存在'
        } else if (status && status >= 500) {
            message = '服务器内部错误'
        } else if (error.code === 'ECONNABORTED') {
            message = '请求超时'
        }

        ElMessage.error(message)
        return Promise.reject(error)
    }
)

export default service

/**
 * 通用请求配置类型（便于业务层扩展）
 */
export type RequestOptions = AxiosRequestConfig
