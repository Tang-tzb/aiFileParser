import {del, get, post, put} from './http'
import type {IdVO, PageResult, SnowflakeId} from '@/types/api'
import type {ProjectCreateDTO, ProjectFormCreateDTO, ProjectFormVO, ProjectUpdateDTO, ProjectVO} from '@/types/project'
import type {FileRecordVO} from '@/types/file'

/**
 * 项目模块 API
 * - 对接后端 ProjectController
 * - 所有 ID 用字符串传递，避免雪花大整数精度丢失
 */

// 创建项目（后端返回 Result<IdVO>；拆包后为 {id} 对象，API 层取 .id 保证对外是字符串契约）
export async function createProject(data: ProjectCreateDTO): Promise<SnowflakeId> {
    const res = await post<IdVO>('/project', data)
    return res.id
}

// 查询项目详情
export function getProject(id: SnowflakeId) {
    return get<ProjectVO>(`/project/${id}`)
}

// 分页查询项目列表（创建时间倒序）
export function getProjectPage(params: { pageNum?: number; pageSize?: number }) {
    return get<PageResult<ProjectVO>>('/project/page', params as Record<string, unknown>)
}

// 编辑项目（非空字段更新；projectNo 不可修改）
export function updateProject(id: SnowflakeId, data: ProjectUpdateDTO) {
    return put<void>(`/project/${id}`, data)
}

// 删除项目（逻辑删除）
export function deleteProject(id: SnowflakeId) {
    return del<void>(`/project/${id}`)
}

// ================ 文件关联 ================

// 分页查询项目下文件列表
export function getProjectFiles(id: SnowflakeId, params: { pageNum?: number; pageSize?: number }) {
    return get<PageResult<FileRecordVO>>(`/project/${id}/files`, params as Record<string, unknown>)
}

// 关联已有文件到项目（一个文件至多归属一项目；关联本项目幂等；已归属他项目返回 6003）
export function associateFile(id: SnowflakeId, fileId: SnowflakeId) {
    return post<void>(`/project/${id}/file/${fileId}`)
}

// 解除文件与项目的关联（未归属该项目返回 6004）
export function dissociateFile(id: SnowflakeId, fileId: SnowflakeId) {
    return del<void>(`/project/${id}/file/${fileId}`)
}

// ================ 表单实例 ================

// 绑定表单到项目（后端返回 Result<IdVO>；重复绑定返回 6005）
export async function bindProjectForm(id: SnowflakeId, data: ProjectFormCreateDTO): Promise<SnowflakeId> {
    const res = await post<IdVO>(`/project/${id}/form`, data)
    return res.id
}

// 分页查询项目下表单实例列表
export function getProjectForms(id: SnowflakeId, params: { pageNum?: number; pageSize?: number }) {
    return get<PageResult<ProjectFormVO>>(`/project/${id}/forms`, params as Record<string, unknown>)
}

// 查询表单实例详情（Phase B UI 用行数据展示，函数备用）
export function getProjectForm(id: SnowflakeId, projectFormId: SnowflakeId) {
    return get<ProjectFormVO>(`/project/${id}/form/${projectFormId}`)
}
