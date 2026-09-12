import type {SnowflakeId} from './api'

/**
 * 项目状态枚举（对应后端 ProjectStatus）
 */
export enum ProjectStatus {
    ACTIVE = 'ACTIVE',
    ARCHIVED = 'ARCHIVED'
}

/**
 * 项目状态中文映射（便于 UI 展示）
 */
export const PROJECT_STATUS_LABELS: Record<ProjectStatus, string> = {
    [ProjectStatus.ACTIVE]: '进行中',
    [ProjectStatus.ARCHIVED]: '已归档'
}

/**
 * 项目表单实例状态枚举（对应后端 ProjectFormStatus）
 */
export enum ProjectFormStatus {
    ACTIVE = 'ACTIVE',
    ARCHIVED = 'ARCHIVED'
}

/**
 * 项目表单实例状态中文映射（便于 UI 展示）
 */
export const PROJECT_FORM_STATUS_LABELS: Record<ProjectFormStatus, string> = {
    [ProjectFormStatus.ACTIVE]: '有效',
    [ProjectFormStatus.ARCHIVED]: '归档'
}

/**
 * 创建项目请求 DTO
 * - projectNo 全库唯一，重复返回错误码 6002
 */
export interface ProjectCreateDTO {
    projectNo: string
    projectName: string
    description?: string
}

/**
 * 编辑项目请求 DTO
 * - 全字段可选，非空才更新；projectNo 不可修改
 */
export interface ProjectUpdateDTO {
    projectName?: string
    description?: string
    status?: ProjectStatus
}

/**
 * 项目视图 VO
 */
export interface ProjectVO {
    projectId: SnowflakeId
    projectNo: string
    projectName: string
    description?: string
    status: ProjectStatus
    createTime: string
    updateTime: string
}

/**
 * 绑定表单到项目请求 DTO
 */
export interface ProjectFormCreateDTO {
    formId: SnowflakeId
}

/**
 * 项目表单实例视图 VO
 * - 字段值明细不在本 VO 内，结构化事实通过助手响应 structuredData 获取
 */
export interface ProjectFormVO {
    projectFormId: SnowflakeId
    projectId: SnowflakeId
    formId: SnowflakeId
    formName: string
    sourceFileId?: SnowflakeId
    version: number
    status: ProjectFormStatus
    createTime: string
    updateTime: string
}
