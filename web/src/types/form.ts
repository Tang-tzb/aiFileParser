/**
 * 表单字段类型枚举（对应后端 FieldType）
 */
export enum FieldType {
    STRING = 'STRING',
    INTEGER = 'INTEGER',
    DECIMAL = 'DECIMAL',
    DATE = 'DATE',
    BOOLEAN = 'BOOLEAN'
}

/**
 * 字段类型中文映射（便于 UI 展示）
 */
export const FIELD_TYPE_LABELS: Record<FieldType, string> = {
    [FieldType.STRING]: '字符串',
    [FieldType.INTEGER]: '整数',
    [FieldType.DECIMAL]: '小数',
    [FieldType.DATE]: '日期',
    [FieldType.BOOLEAN]: '布尔'
}

/**
 * 创建表单字段请求 DTO
 */
export interface FormFieldCreateDTO {
    fieldName: string
    fieldCode: string
    fieldType: FieldType
    required?: boolean
    description?: string
    sort?: number
}

/**
 * 创建表单请求 DTO
 */
export interface FormCreateDTO {
    formName: string
    description?: string
    fields?: FormFieldCreateDTO[]
}

/**
 * 表单字段视图 VO
 */
export interface FormFieldVO {
    fieldId: number | string
    fieldName: string
    fieldCode: string
    fieldType: FieldType
    required: boolean
    description?: string
    sort: number
}

/**
 * 表单视图 VO
 */
export interface FormVO {
    formId: number | string
    formName: string
    description?: string
    createTime: string
    updateTime: string
    fields: FormFieldVO[]
}
