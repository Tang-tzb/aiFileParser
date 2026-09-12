import type {SnowflakeId} from './api'

/**
 * 证据类型（对应后端 AssistantReferenceVO.type）
 */
export const REFERENCE_TYPE = {
    STRUCTURED: 'STRUCTURED',
    FILE: 'FILE'
} as const
export type ReferenceType = (typeof REFERENCE_TYPE)[keyof typeof REFERENCE_TYPE]

/**
 * 助手对话请求 DTO
 */
export interface AssistantChatRequest {
    /** 会话 ID；可选，空白时服务端生成 UUID 并随响应返回 */
    conversationId?: string
    /** 必填，禁止空白 */
    message: string
}

/**
 * 证据项（type 区分两组，组内无关字段为 null）
 * - STRUCTURED：结构化字段值来源
 * - FILE：文档切片
 */
export interface AssistantReferenceVO {
    type: ReferenceType
    /** 引用标记：S{n}（结构化）/ D{n}（文档） */
    citationId?: string

    // ===== STRUCTURED 组 =====
    fieldCode?: string
    fieldName?: string
    /** 用户可读展示值 */
    rawValue?: string
    /** 后端计算值（仅展示/透传，前端禁止参与计算） */
    normalizedValue?: string
    unit?: string
    sourceFileId?: SnowflakeId
    sourceFileName?: string | null
    sourcePage?: number | null
    sourceChunkId?: string | null

    // ===== FILE 组 =====
    fileId?: SnowflakeId
    fileName?: string
    /** 切片起始页 pageStart */
    page?: number | null
    chunkId?: string | null
}

/**
 * 单条来源值（project_form_field_value 行投影）
 */
export interface FieldValueItem {
    rawValue: string
    normalizedValue: string
    unit?: string
    sourceFileId?: SnowflakeId | null
    sourceFileName?: string | null
    sourcePage?: number | null
    sourceChunkId?: string | null
    /** 置信度 0~1 */
    confidence?: number | null
}

/**
 * 字段维度事实（conflict=true 时 values 完整保留全部来源，后端不裁决）
 */
export interface StructuredFieldFact {
    projectFormId: SnowflakeId
    fieldId?: SnowflakeId | null
    fieldCode: string
    fieldName: string
    /** FieldType 枚举 code */
    fieldType: string
    conflict: boolean
    values: FieldValueItem[]
}

/**
 * 跨项目比较参与单元
 */
export interface ComparisonUnit {
    projectId: SnowflakeId
    projectName: string
    projectFormId: SnowflakeId
    /** 展示优先 rawValue + unit */
    rawValue: string
    /** 计算唯一依据（后端已算，前端只展示） */
    normalizedValue: string
    unit?: string
    /** 仅数值字段非 null；同值并列 1,2,2,4 */
    rank?: number | null
    /** unit.value − current.value */
    diffFromCurrent?: string | null
    diffFromCurrentPercent?: string | null
    sourceFileId?: SnowflakeId | null
    sourceFileName?: string | null
    sourcePage?: number | null
    sourceChunkId?: string | null
}

/**
 * 聚合结果（后端 HALF_UP scale=4）
 */
export interface ComparisonAggregates {
    max: string
    min: string
    sum: string
    avg: string
    count: number
}

/**
 * 被排除单元（禁止静默丢弃，前端须展示排除原因）
 */
export type ComparisonExcludedReason = 'CONFLICT' | 'UNPARSEABLE' | 'UNIT_INCOMPATIBLE'

export interface ComparisonExcludedUnit {
    reason: ComparisonExcludedReason
    projectId: SnowflakeId
    projectName: string
    projectFormId: SnowflakeId
    fieldCode: string
    values: FieldValueItem[]
}

/**
 * 跨项目比较结果（仅 COMPARISON 意图返回）
 */
export interface CrossProjectComparisonVO {
    fieldCode: string
    fieldName: string
    /** 仅 INTEGER/DECIMAL 参与数值计算 */
    fieldType: string
    /** 基准单位 */
    unit?: string
    currentProjectId: SnowflakeId
    targetProjectIds: SnowflakeId[]
    units: ComparisonUnit[]
    aggregates?: ComparisonAggregates | null
    excluded: ComparisonExcludedUnit[]
}

/**
 * 助手对话响应
 */
export interface AssistantChatResponse {
    conversationId: string
    /** 纯自然语言 + [S{n}]/[D{n}] 标记（标记保留，前端渲染上标） */
    answer: string
    /** 可用证据全集（顺序即 Prompt 渲染顺序） */
    references: AssistantReferenceVO[]
    /** LLM 实际引用子集（answer 标记的映射结果） */
    citations: AssistantReferenceVO[]
    /** 注入 LLM 的字段事实（渲染冲突卡片） */
    structuredData: StructuredFieldFact[]
    usedProjects: SnowflakeId[]
    usedFiles: SnowflakeId[]
    comparisonData?: CrossProjectComparisonVO | null
}

/**
 * SSE delta 事件载荷（Phase K 流式协议：回答文本增量）
 */
export interface AssistantStreamDeltaEvent {
    delta: string
}

/**
 * SSE error 事件载荷（Phase K 流式协议：编排失败消息）
 */
export interface AssistantStreamErrorEvent {
    message: string
}

/**
 * 聊天消息（UI 层模型，与 API DTO 分离）
 * - 禁止把 AssistantChatResponse[] 直接当聊天记录
 * - response 仅 assistant 消息携带，含引用/冲突/比较数据（Phase E 消费）
 */
export interface ChatMessage {
    role: 'user' | 'assistant'
    /** user 消息原文 / assistant 的 answer */
    content: string
    /** 本地时间 ISO 字符串 */
    timestamp: string
    /** 仅 assistant 消息携带 */
    response?: AssistantChatResponse
    /** assistant 消息请求中占位（尚未输出任何内容） */
    loading?: boolean
    /** assistant 消息流式输出中（Phase K：已输出部分内容，等待 final 收尾） */
    streaming?: boolean
}
