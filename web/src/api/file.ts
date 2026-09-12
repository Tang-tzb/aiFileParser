import {get, post} from './http'
import type {PageResult} from '@/types/api'
import type {FileRecordVO, FileUploadVO} from '@/types/file'

/**
 * 文件模块 API
 * - 对接后端 FileController
 */

/**
 * 上传文件（multipart/form-data）
 * - 手动控制 FormData，不依赖 el-upload 默认上传行为
 * - 支持上传进度回调，便于 UI 展示进度条
 *
 * @param file    待上传的 File 对象
 * @param options 可选：projectId（项目归属，后端 UploadFileDTO 字符串序列化，无效项目返回 6007）、onProgress（接收 0-100 的百分比）
 */
export function uploadFile(
    file: File,
    options?: { projectId?: string; onProgress?: (percent: number) => void }
) {
    const formData = new FormData()
    formData.append('file', file)
    // 项目归属（可选；无则不归属任何项目）
    if (options?.projectId) {
        formData.append('projectId', options.projectId)
    }

    return post<FileUploadVO>('/file/upload', formData, {
        headers: {'Content-Type': 'multipart/form-data'},
        onUploadProgress: (e) => {
            if (options?.onProgress && e.total) {
                options.onProgress(Math.round((e.loaded * 100) / e.total))
            }
        }
    })
}

/**
 * 分页查询文件列表（按上传时间倒序）
 * - AI 填报页文件下拉选择用
 */
export function getFileList(params: { pageNum?: number; pageSize?: number }) {
    return get<PageResult<FileRecordVO>>('/file/page', params as Record<string, unknown>)
}
