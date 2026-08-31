import {get, post, del} from './http'
import type {PageResult} from '@/types/api'
import type {FormCreateDTO, FormVO, FormFieldCreateDTO} from '@/types/form'

/**
 * 表单模块 API
 * - 对接后端 FormController
 * - 具体 UI 调用在 Phase 2 实现
 */

// 创建表单（含字段）
export function createForm(data: FormCreateDTO) {
    return post<string>('/form/create', data)
}

// 分页查询表单列表（列表场景 fields 为空，字段详情走 getFormDetail）
export function getFormList(params: { pageNum?: number; pageSize?: number }) {
    return get<PageResult<FormVO>>('/form/page', params as Record<string, unknown>)
}

// 查询表单详情（含字段列表）。ID 用字符串传递，避免大整数精度丢失
export function getFormDetail(id: number | string) {
    return get<FormVO>(`/form/${id}`)
}

// 单独添加字段
export function addField(formId: number | string, data: FormFieldCreateDTO) {
    return post<string>(`/form/${formId}/field`, data)
}

// 删除字段
export function deleteField(formId: number | string, fieldId: number | string) {
    return del<void>(`/form/${formId}/field/${fieldId}`)
}

// 删除表单（级联软删其下所有字段）。ID 用字符串传递，避免大整数精度丢失
export function deleteForm(id: number | string) {
    return del<void>(`/form/${id}`)
}
