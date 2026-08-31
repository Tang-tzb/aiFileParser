import type {FileUploadVO} from '@/types/file'

/**
 * 最近一次上传文件记录的本地持久化
 *
 * - 用途：上传成功后保存 fileId，供 AI 填报页（Phase 4/5）预填关联文件
 * - fileId 按字符串保存，避免雪花算法大整数在 JS Number 中精度丢失
 */

const STORAGE_KEY = 'aifp_last_upload'

export interface LastUpload {
    fileId: string
    fileName: string
    fileType: string
    createTime: string
}

/** 保存最近一次上传成功记录（fileId 统一转字符串） */
export function setLastUpload(vo: FileUploadVO): void {
    const record: LastUpload = {
        fileId: String(vo.fileId),
        fileName: vo.fileName,
        fileType: String(vo.fileType),
        createTime: vo.createTime
    }
    localStorage.setItem(STORAGE_KEY, JSON.stringify(record))
}

/** 读取最近一次上传记录，无则返回 null */
export function getLastUpload(): LastUpload | null {
    const raw = localStorage.getItem(STORAGE_KEY)
    if (!raw) return null
    try {
        return JSON.parse(raw) as LastUpload
    } catch {
        return null
    }
}

/** 清除最近一次上传记录 */
export function clearLastUpload(): void {
    localStorage.removeItem(STORAGE_KEY)
}
