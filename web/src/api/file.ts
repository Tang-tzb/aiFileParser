import {post} from './http'
import type {FileUploadVO} from '@/types/file'

/**
 * 文件模块 API
 * - 对接后端 FileController
 */

/**
 * 上传文件（multipart/form-data）
 * - 手动控制 FormData，不依赖 el-upload 默认上传行为
 * - 支持上传进度回调，便于 UI 展示进度条
 *
 * @param file      待上传的 File 对象
 * @param onProgress 可选，接收 0-100 的百分比
 */
export function uploadFile(file: File, onProgress?: (percent: number) => void) {
    const formData = new FormData()
    formData.append('file', file)

    return post<FileUploadVO>('/file/upload', formData, {
        headers: {'Content-Type': 'multipart/form-data'},
        onUploadProgress: (e) => {
            if (onProgress && e.total) {
                onProgress(Math.round((e.loaded * 100) / e.total))
            }
        }
    })
}
