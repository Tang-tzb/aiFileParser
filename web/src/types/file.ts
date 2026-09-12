/**
 * 文件类型枚举（对应后端 FileType）
 */
export enum FileType {
    PDF = 'PDF',
    EXCEL = 'EXCEL',
    WORD = 'WORD',
    TXT = 'TXT',
    OTHER = 'OTHER'
}

/**
 * 文件处理状态枚举（对应后端 FileStatus）
 * 流转：UPLOADED → PARSING → VECTORING → EXTRACTING → SUCCESS / FAILED
 */
export enum FileStatus {
    UPLOADED = 'UPLOADED',
    PARSING = 'PARSING',
    VECTORING = 'VECTORING',
    EXTRACTING = 'EXTRACTING',
    SUCCESS = 'SUCCESS',
    FAILED = 'FAILED'
}

/**
 * 文件类型中文标签映射
 */
export const FILE_TYPE_LABELS: Record<FileType, string> = {
    [FileType.PDF]: 'PDF文档',
    [FileType.EXCEL]: 'Excel表格',
    [FileType.WORD]: 'Word文档',
    [FileType.TXT]: 'txt文件',
    [FileType.OTHER]: '其他类型'
}

/**
 * 文件状态中文标签映射
 */
export const FILE_STATUS_LABELS: Record<FileStatus, string> = {
    [FileStatus.UPLOADED]: '已上传',
    [FileStatus.PARSING]: '解析中',
    [FileStatus.VECTORING]: '向量化中',
    [FileStatus.EXTRACTING]: '字段抽取中',
    [FileStatus.SUCCESS]: '处理成功',
    [FileStatus.FAILED]: '处理失败'
}

/**
 * el-upload accept 扩展名（与后端 FileType 合法扩展名一致）
 */
export const ACCEPT_EXT = '.pdf,.xlsx,.xls,.docx,.doc,.txt'

/**
 * 文件上传响应 VO
 * - fileId 用 number | string 兼容后端字符串输出（避免大整数精度丢失）
 */
export interface FileUploadVO {
    fileId: number | string
    fileName: string
    fileType: FileType
    filePath: string
    status: FileStatus
    createTime: string
}

/**
 * 文件记录展示 VO（GET /file/page 返回，供 AI 填报页文件下拉用）
 */
export interface FileRecordVO {
    fileId: number | string
    fileName: string
    fileType: FileType
    filePath: string
    status: FileStatus
    createTime: string
    updateTime?: string
    /** 所属项目ID（可空；未关联项目时为空。已归属其他项目的文件不可重复关联） */
    projectId?: number | string
}
