# 项目（Project）+ 项目助手（Assistant）前端开发文档

> 版本：v1.0 ｜ 更新日期：2026-09-12
> 前置阅读：[frontend.md](./frontend.md)（前端现状架构与通用约定）
> 契约依据：后端已实现代码（`ProjectController` / `ProjectAssistantController` / `FileController` 等），非需求建议稿
> 修订依据：《前端开发计划修改建议.md》12 条意见已全部吸收（裸 Long 后端修复、Assistant Tab 化、SnowflakeId 统一等）

---

## 1. 文档概述

本文档是"项目（Project）+ 项目助手（Project Assistant）"功能的**前端增量开发指南**。现有前端架构（types → api → stores →
views → router/MainLayout）不需要推翻，本文档只定义增量内容：

- 新增 `types/project.ts`、`types/assistant.ts` 类型模块
- 新增 `api/project.ts`、`api/assistant.ts` 接口模块
- 新增项目列表 / 创建 / 详情三个页面与助手组件
- 改造 FileUpload / AiFill 两个既有页面以支持项目上下文

**硬性前提（Phase 0）**：后端 `POST /project` 与 `POST /project/{id}/form` 当前返回裸 `Long` ID，存在 JS 精度丢失风险。须先完成契约修复（见
§3、§12 Phase 0），前端**不做回查兜底**。

---

## 2. 新增能力总览

Project 是业务聚合根，一个项目关联多个表单实例与多个文件：

```
Project（聚合根）
├── 1 ─── N ProjectForm（项目表单实例，指向 FormDefinition）
│           └── N ProjectFormFieldValue（结构化字段值，含来源溯源）
├── 1 ─── N File（一个文件至多归属一个项目）
│           └── 解析后的 Milvus Chunk（metadata 含 projectId）
└── Project Assistant（项目作用域对话：结构化事实 + 项目 RAG → LLM）
```

与现有能力的关系：

| 现有能力                                       | 增量变化                         |
|--------------------------------------------|------------------------------|
| 文件上传 `/file/upload`                        | 新增可选 `projectId` 参数          |
| 异步任务 `POST /task`                          | 请求体新增可选 `projectId`（校验文件归属）  |
| 表单定义 `/form/*`                             | 不变；项目通过 ProjectForm 实例引用表单定义 |
| AI 填报 `/fill/index`                        | 支持项目上下文（文件下拉按项目过滤）           |
| 新增助手 `/project/{projectId}/assistant/chat` | 全新能力，挂在项目详情页 Tab 内           |

---

## 3. 前后端契约注意事项（前置专章）

以下差异点在开发前必须知晓，**第 1、2 行是 Phase 0 的准入条件**：

| #  | 问题               | 当前状态                                                                                       | 前端处理                                                                                      | 后端修复建议                                                                              |
|----|------------------|--------------------------------------------------------------------------------------------|-------------------------------------------------------------------------------------------|-------------------------------------------------------------------------------------|
| 1  | **项目创建返回 ID**    | `POST /project` 返回 `Result<Long>`（裸数字，雪花 ID 超出 `Number.MAX_SAFE_INTEGER`，JSON.parse 即精度丢失） | **禁止开发依赖此接口的页面**；不做回查兜底。Phase 0 后：http 拆包得到 `{id}` 对象，**不能直接当字符串**，API 层统一封装取 `.id`（§6.1） | **Phase 0 必修**：改 `Result<IdVO>`（新增 `IdVO{id}`，`@JsonSerialize(ToStringSerializer)`） |
| 2  | **表单绑定返回 ID**    | `POST /project/{id}/form` 返回 `Result<Long>`（同上）                                            | 同上                                                                                        | **Phase 0 必修**：同上                                                                   |
| 3  | Task 的 projectId | `TaskStartRequest.projectId` 可选；传入时后端校验文件归属该项目                                             | 项目上下文内**必须传**；独立填报路径不传（字段缺省，保持旧兼容）                                                        | 已实现，无需修                                                                             |
| 4  | 上传的 projectId    | `POST /file/upload` 可选 multipart 参数                                                        | 项目上下文内**必须传**；页面明示归属项目                                                                    | 已实现，无需修                                                                             |
| 5  | conversationId   | 请求可选；空白时服务端生成 UUID 并随响应返回                                                                  | 前端按 projectId 隔离生成与缓存（§8）                                                                 | 已实现，无需修                                                                             |
| 6  | references       | 本次回答**可使用的证据全集**（结构化 + RAG），非"实际引用"                                                        | 渲染为来源集合（证据池）                                                                              | —                                                                                   |
| 7  | citations        | LLM **实际引用子集**（answer 中 `[S{n}]/[D{n}]` 标记经后端解析映射）                                         | 上标/来源卡片的数据源，**唯一依据**                                                                      | —                                                                                   |
| 8  | structuredData   | 字段事实含 `conflict` 标记；冲突时 `values` 完整保留全部来源值，**后端不裁决**                                       | 冲突双视觉状态卡片（§8），前端同样**不得裁决**                                                                | —                                                                                   |
| 9  | comparisonData   | rank / 聚合 / 差值 / 排除明细**全部由后端 BigDecimal 计算**                                               | `ComparisonResult.vue` 只展示，**禁止前端用 normalizedValue 自行计算**                                 | —                                                                                   |
| 10 | 助手文件清单上限         | 后端助手内部文件清单使用 `PageQuery(1,100)`                                                            | 仅助手场景接受此限制；**项目文件 Tab 一律走 `GET /project/{id}/files` 正常分页**，两者是不同场景                        | —                                                                                   |

其余全局约定沿用 frontend.md §15：ID 一律字符串化、错误统一由 Axios 拦截器提示、API 返回值即 `Result.data`。

---

## 4. 新增 API 对接清单

### 4.1 项目 CRUD（ProjectController）

| 方法     | 前端路径            | 入参                   | 返回                                         | 备注                   |
|--------|-----------------|----------------------|--------------------------------------------|----------------------|
| POST   | `/project`      | `ProjectCreateDTO`   | `Result<Long>` →【Phase 0 后 `Result<IdVO>`】 | projectNo 全库唯一       |
| GET    | `/project/{id}` | —                    | `Result<ProjectVO>`                        |                      |
| GET    | `/project/page` | `pageNum`、`pageSize` | `Result<PageResult<ProjectVO>>`            | 创建时间倒序               |
| PUT    | `/project/{id}` | `ProjectUpdateDTO`   | `Result<Void>`                             | 非空字段更新；projectNo 不可改 |
| DELETE | `/project/{id}` | —                    | `Result<Void>`                             | 逻辑删除                 |

创建请求示例：

```json
{
  "projectNo": "PRJ-2026-001",
  "projectName": "职业教育园一期",
  "description": "示例项目描述"
}
```

ProjectVO 响应示例（`data` 部分，projectId 已序列化为字符串）：

```json
{
  "projectId": "1912345678901234567",
  "projectNo": "PRJ-2026-001",
  "projectName": "职业教育园一期",
  "description": "示例项目描述",
  "status": "ACTIVE",
  "createTime": "2026-09-12 10:00:00",
  "updateTime": "2026-09-12 10:00:00"
}
```

编辑请求示例（全字段可选，非空才更新）：

```json
{
  "projectName": "职业教育园一期（更名）",
  "status": "ARCHIVED"
}
```

### 4.2 项目文件管理

| 方法     | 前端路径                          | 入参                   | 返回                                 | 备注                         |
|--------|-------------------------------|----------------------|------------------------------------|----------------------------|
| GET    | `/project/{id}/files`         | `pageNum`、`pageSize` | `Result<PageResult<FileRecordVO>>` | **正常分页**，上传时间倒序；文件 Tab 数据源 |
| POST   | `/project/{id}/file/{fileId}` | —                    | `Result<Void>`                     | 一个文件至多归属一个项目；重复关联本项目幂等     |
| DELETE | `/project/{id}/file/{fileId}` | —                    | `Result<Void>`                     | 文件须当前归属该项目                 |

### 4.3 项目表单实例

| 方法   | 前端路径                                 | 入参                             | 返回                                         | 备注                    |
|------|--------------------------------------|--------------------------------|--------------------------------------------|-----------------------|
| POST | `/project/{id}/form`                 | `ProjectFormCreateDTO{formId}` | `Result<Long>` →【Phase 0 后 `Result<IdVO>`】 | 同项目同表单唯一，重复绑定拒绝（6005） |
| GET  | `/project/{id}/forms`                | `pageNum`、`pageSize`           | `Result<PageResult<ProjectFormVO>>`        | 创建时间倒序                |
| GET  | `/project/{id}/form/{projectFormId}` | —                              | `Result<ProjectFormVO>`                    | 实例不属于该项目时按不存在处理       |

ProjectFormVO 响应示例：

```json
{
  "projectFormId": "1912345678901234999",
  "projectId": "1912345678901234567",
  "formId": "1912345678901234000",
  "formName": "项目基本信息",
  "sourceFileId": "1912345678901234568",
  "version": 1,
  "status": "ACTIVE",
  "createTime": "2026-09-12 10:05:00",
  "updateTime": "2026-09-12 10:05:00"
}
```

> 字段值明细不在本 VO 内；结构化事实通过助手响应 `structuredData` 获取（§4.5）。

### 4.4 既有接口增量

**文件上传**（multipart，新增可选 `projectId`）：

```
POST /file/upload
Content-Type: multipart/form-data
字段：file（文件二进制）、projectId（可选；项目上下文内必传）
```

**任务启动**（请求体新增可选 `projectId`）：

```json
{
  "projectId": "1912345678901234567",
  "formId": "1912345678901234000",
  "fileId": "1912345678901234568"
}
```

> 独立填报路径（无项目上下文）不传 `projectId` 字段（undefined 时字段缺省），后端按旧逻辑反查归属。响应 `TaskStartVO`
> 仍为 `{taskId, fileId, formId, createTime}`，无 projectId。

### 4.5 项目助手对话（Phase K：SSE 流式）

```
POST /project/{projectId}/assistant/chat
Accept: text/event-stream
```

**响应为 SSE 事件流**（`Content-Type: text/event-stream`，连接超时 120s），事件协议：

| 事件名     | 载荷                             | 说明                 |
|---------|--------------------------------|--------------------|
| `delta` | `{"delta":"..."}`              | 回答文本增量，每块一次，按序追加渲染 |
| `final` | AssistantChatResponse（下方 JSON） | 完整响应收尾，含引用/冲突/比较数据 |
| `error` | `{"message":"..."}`            | 编排失败消息，随后连接关闭      |

- 守门失败（403/6001）与参数校验失败发生在 SSE 连接建立前：HTTP 200 + 统一 `Result` JSON（非事件流），与 §7 错误处理语义一致。
- 前端用 **fetch + ReadableStream** 解析（axios 不支持流式读取；POST 请求也无法用 EventSource），实现见 `api/assistant.ts`
  的 `chatStream`。

请求示例：

```json
{
  "conversationId": "550e8400-e29b-41d4-a716-446655440000",
  "message": "这个项目的总投资是多少？"
}
```

响应示例（`final` 事件载荷，关键块展开）：

```json
{
  "conversationId": "550e8400-e29b-41d4-a716-446655440000",
  "answer": "项目总投资为 12.5 亿元 [S1]，该值来源于职教园一期施工许可证.pdf 第 3 页。",
  "references": [
    {
      "type": "STRUCTURED",
      "citationId": "S1",
      "fieldCode": "totalInvestment",
      "fieldName": "项目总投资",
      "rawValue": "12.5亿元",
      "normalizedValue": "1250000000",
      "unit": "元",
      "sourceFileId": "1912345678901234568",
      "sourceFileName": "职教园一期施工许可证.pdf",
      "sourcePage": 3,
      "sourceChunkId": "chunk-xxx"
    },
    {
      "type": "FILE",
      "citationId": "D1",
      "fileId": "1912345678901234568",
      "fileName": "职教园一期施工许可证.pdf",
      "page": 3,
      "chunkId": "chunk-xxx"
    }
  ],
  "citations": [
    { "type": "STRUCTURED", "citationId": "S1", "...": "references 成员子集" }
  ],
  "structuredData": [
    {
      "projectFormId": "1912345678901234999",
      "fieldId": "1912345678901234001",
      "fieldCode": "totalInvestment",
      "fieldName": "项目总投资",
      "fieldType": "DECIMAL",
      "conflict": false,
      "values": [
        {
          "rawValue": "12.5亿元",
          "normalizedValue": "1250000000",
          "unit": "元",
          "sourceFileId": "1912345678901234568",
          "sourceFileName": "职教园一期施工许可证.pdf",
          "sourcePage": 3,
          "sourceChunkId": "chunk-xxx",
          "confidence": 0.95
        }
      ]
    }
  ],
  "usedProjects": ["1912345678901234567"],
  "usedFiles": ["1912345678901234568"],
  "comparisonData": null
}
```

关键渲染语义：

- `answer` 中的 `[S{n}]`（结构化证据）与 `[D{n}]`（文档片段）是引用锚点；`citations[]` 是其实际指向的证据成员。
- `comparisonData` 仅在跨项目比较意图时非 null，含 `units[]`（rank/diffFromCurrent/diffFromCurrentPercent）、`aggregates`
  （max/min/sum/avg/count）、`excluded[]`（CONFLICT / UNPARSEABLE / UNIT_INCOMPATIBLE 三种排除原因）。
- `structuredData[].conflict=true` 表示同字段存在多个不同 normalizedValue，`values` 完整保留全部来源。

---

## 5. 新增类型定义规格

### 5.1 全局 ID 别名（`types/api.ts` 追加）

```typescript
/** 雪花 ID 类型：后端统一序列化为字符串，前端禁止使用 number 承接 */
export type SnowflakeId = string

/** 创建类接口通用返回 VO：后端 Result<IdVO>，http 拦截器拆包后即本对象 */
export interface IdVO {
  id: SnowflakeId
}
```

> 新代码中 projectId / fileId / formId / projectFormId / fieldId 一律使用 `SnowflakeId`，不再写 `number | string`
> 联合类型。既有 `types/form.ts`、`types/file.ts`、`types/task.ts` 保持不动（避免回归），新模块从源头上规范。
>
> **IdVO 映射约定**：`createProject` / `bindProjectForm` 等"创建返回 ID"接口，后端返回 `Result<IdVO>`，http
> 拆包后是 `{id: "..."}` 对象；**API 层必须取 `.id` 后再返回**（§6.1），页面层只接触字符串，禁止把 `{id}` 对象当 ID 使用或层层透传。

### 5.2 `types/project.ts`

```typescript
/** 项目状态枚举（对应后端 ProjectStatus） */
export enum ProjectStatus {
  ACTIVE = 'ACTIVE',        // 进行中
  ARCHIVED = 'ARCHIVED'     // 已归档
}

export const PROJECT_STATUS_LABELS: Record<ProjectStatus, string> = {
  [ProjectStatus.ACTIVE]: '进行中',
  [ProjectStatus.ARCHIVED]: '已归档'
}

/** 项目表单实例状态（对应后端 ProjectFormStatus） */
export enum ProjectFormStatus {
  ACTIVE = 'ACTIVE',        // 有效
  ARCHIVED = 'ARCHIVED'     // 归档
}

export const PROJECT_FORM_STATUS_LABELS: Record<ProjectFormStatus, string> = {
  [ProjectFormStatus.ACTIVE]: '有效',
  [ProjectFormStatus.ARCHIVED]: '归档'
}

/** 创建项目 DTO */
export interface ProjectCreateDTO {
  projectNo: string      // 必填，≤64 字符，全库唯一
  projectName: string    // 必填，≤100 字符
  description?: string   // ≤500 字符
}

/** 编辑项目 DTO（全字段可选，非空才更新；projectNo 不可改） */
export interface ProjectUpdateDTO {
  projectName?: string
  description?: string
  status?: ProjectStatus
}

/** 项目 VO */
export interface ProjectVO {
  projectId: SnowflakeId
  projectNo: string
  projectName: string
  description?: string
  status: ProjectStatus
  createTime: string
  updateTime: string
}

/** 绑定表单到项目 DTO */
export interface ProjectFormCreateDTO {
  formId: SnowflakeId    // 必填
}

/** 项目表单实例 VO */
export interface ProjectFormVO {
  projectFormId: SnowflakeId
  projectId: SnowflakeId
  formId: SnowflakeId
  formName: string            // 冗余展示，实时查询表单定义
  sourceFileId?: SnowflakeId  // 首个来源文件（抽取回填，可空）
  version: number
  status: ProjectFormStatus
  createTime: string
  updateTime: string
}
```

### 5.3 `types/assistant.ts`（API 层 DTO/VO）

```typescript
import type { SnowflakeId } from './api'

/** 证据类型 */
export const REFERENCE_TYPE = {
  STRUCTURED: 'STRUCTURED',  // 结构化字段值来源
  FILE: 'FILE'               // 文档切片
} as const
export type ReferenceType = (typeof REFERENCE_TYPE)[keyof typeof REFERENCE_TYPE]

/** 助手对话请求 */
export interface AssistantChatRequest {
  conversationId?: string  // 可选；空白时服务端生成 UUID 并随响应返回
  message: string          // 必填，禁止空白
}

/** 证据项（type 区分两组，组内无关字段为 null） */
export interface AssistantReferenceVO {
  type: ReferenceType
  citationId?: string      // 引用标记：S{n}（结构化）/ D{n}（文档）

  // ===== STRUCTURED 组 =====
  fieldCode?: string
  fieldName?: string
  rawValue?: string          // 用户可读展示值
  normalizedValue?: string   // 后端计算值（仅展示/透传，前端禁止参与计算）
  unit?: string
  sourceFileId?: SnowflakeId
  sourceFileName?: string | null
  sourcePage?: number | null
  sourceChunkId?: string | null

  // ===== FILE 组 =====
  fileId?: SnowflakeId
  fileName?: string
  page?: number | null       // 切片起始页 pageStart
  chunkId?: string | null
}

/** 单条来源值（project_form_field_value 行投影） */
export interface FieldValueItem {
  rawValue: string
  normalizedValue: string
  unit?: string
  sourceFileId?: SnowflakeId | null
  sourceFileName?: string | null
  sourcePage?: number | null
  sourceChunkId?: string | null
  confidence?: number | null   // 0~1
}

/** 字段维度事实（conflict=true 时 values 完整保留全部来源，后端不裁决） */
export interface StructuredFieldFact {
  projectFormId: SnowflakeId
  fieldId?: SnowflakeId | null
  fieldCode: string
  fieldName: string
  fieldType: string         // FieldType 枚举 code
  conflict: boolean
  values: FieldValueItem[]
}

/** 跨项目比较参与单元 */
export interface ComparisonUnit {
  projectId: SnowflakeId
  projectName: string
  projectFormId: SnowflakeId
  rawValue: string            // 展示优先 rawValue + unit
  normalizedValue: string     // 计算唯一依据（后端已算，前端只展示）
  unit?: string
  rank?: number | null        // 仅数值字段非 null；同值并列 1,2,2,4
  diffFromCurrent?: string | null    // unit.value − current.value
  diffFromCurrentPercent?: string | null
  sourceFileId?: SnowflakeId | null
  sourceFileName?: string | null
  sourcePage?: number | null
  sourceChunkId?: string | null
}

/** 聚合结果（后端 HALF_UP scale=4） */
export interface ComparisonAggregates {
  max: string
  min: string
  sum: string
  avg: string
  count: number
}

/** 被排除单元（禁止静默丢弃，前端须展示排除原因） */
export type ComparisonExcludedReason = 'CONFLICT' | 'UNPARSEABLE' | 'UNIT_INCOMPATIBLE'

export interface ComparisonExcludedUnit {
  reason: ComparisonExcludedReason
  projectId: SnowflakeId
  projectName: string
  projectFormId: SnowflakeId
  fieldCode: string
  values: FieldValueItem[]
}

/** 跨项目比较结果（仅 COMPARISON 意图返回） */
export interface CrossProjectComparisonVO {
  fieldCode: string
  fieldName: string
  fieldType: string           // 仅 INTEGER/DECIMAL 参与数值计算
  unit?: string               // 基准单位
  currentProjectId: SnowflakeId
  targetProjectIds: SnowflakeId[]
  units: ComparisonUnit[]
  aggregates?: ComparisonAggregates | null
  excluded: ComparisonExcludedUnit[]
}

/** 助手对话响应 */
export interface AssistantChatResponse {
  conversationId: string
  answer: string                          // 纯自然语言 + [S{n}]/[D{n}] 标记（标记保留，前端渲染上标）
  references: AssistantReferenceVO[]      // 可用证据全集（顺序即 Prompt 渲染顺序）
  citations: AssistantReferenceVO[]       // LLM 实际引用子集（answer 标记的映射结果）
  structuredData: StructuredFieldFact[]   // 注入 LLM 的字段事实（渲染冲突卡片）
  usedProjects: SnowflakeId[]
  usedFiles: SnowflakeId[]
  comparisonData?: CrossProjectComparisonVO | null
}
```

### 5.4 UI 层模型（与 API DTO 分离）

**禁止**把 `AssistantChatResponse[]` 直接当聊天记录。UI 消息模型：

```typescript
/** 聊天消息（UI 层，types/assistant.ts 内定义） */
export interface ChatMessage {
  role: 'user' | 'assistant'
  content: string                 // user 消息原文 / assistant 的 answer
  timestamp: string               // 本地时间 ISO 字符串
  response?: AssistantChatResponse  // 仅 assistant 消息携带，含引用/冲突/比较数据
  loading?: boolean               // assistant 消息请求中占位
}
```

### 5.5 `utils/lastUpload.ts` 扩展

```typescript
export interface LastUpload {
  fileId: string
  fileName: string
  fileType: string
  projectId?: string      // 新增：所属项目（独立上传时缺省）
  projectName?: string    // 新增：项目名称（回显用）
  createTime: string
}
```

> 目的：项目 A 上传 → 跳 AI 填报 → localStorage 仍能还原项目上下文，避免只知 fileId 而项目信息丢失。

---

## 6. 新增 API 层设计

### 6.1 `api/project.ts`

```typescript
import {get, post, put, del} from './http'
import type {IdVO, PageResult, SnowflakeId} from '@/types/api'
import type {FileRecordVO} from '@/types/file'
import type {
  ProjectCreateDTO, ProjectUpdateDTO, ProjectVO,
  ProjectFormCreateDTO, ProjectFormVO
} from '@/types/project'

/** 创建项目（后端返回 Result<IdVO>；拆包后为 {id} 对象，API 层取 .id 保证对外是字符串契约） */
export async function createProject(data: ProjectCreateDTO): Promise<SnowflakeId> {
  const res = await post<IdVO>('/project', data)
  return res.id
}

/** 项目详情 */
export function getProject(id: SnowflakeId) {
  return get<ProjectVO>(`/project/${id}`)
}

/** 分页项目列表（创建时间倒序） */
export function getProjectPage(params: { pageNum?: number; pageSize?: number }) {
  return get<PageResult<ProjectVO>>('/project/page', params as Record<string, unknown>)
}

/** 编辑项目（非空字段更新） */
export function updateProject(id: SnowflakeId, data: ProjectUpdateDTO) {
  return put<void>(`/project/${id}`, data)
}

/** 删除项目（逻辑删除） */
export function deleteProject(id: SnowflakeId) {
  return del<void>(`/project/${id}`)
}

/** 分页查询项目文件（文件 Tab 数据源，正常分页） */
export function getProjectFiles(id: SnowflakeId, params: { pageNum?: number; pageSize?: number }) {
  return get<PageResult<FileRecordVO>>(`/project/${id}/files`, params as Record<string, unknown>)
}

/** 关联文件到项目（至多归属一个项目；重复关联本项目幂等） */
export function associateFile(id: SnowflakeId, fileId: SnowflakeId) {
  return post<void>(`/project/${id}/file/${fileId}`)
}

/** 解除文件关联 */
export function dissociateFile(id: SnowflakeId, fileId: SnowflakeId) {
  return del<void>(`/project/${id}/file/${fileId}`)
}

/** 绑定表单到项目（后端返回 Result<IdVO>；API 层取 .id，同 createProject 约定） */
export async function bindProjectForm(id: SnowflakeId, data: ProjectFormCreateDTO): Promise<SnowflakeId> {
  const res = await post<IdVO>(`/project/${id}/form`, data)
  return res.id
}

/** 分页查询项目表单实例 */
export function getProjectForms(id: SnowflakeId, params: { pageNum?: number; pageSize?: number }) {
  return get<PageResult<ProjectFormVO>>(`/project/${id}/forms`, params as Record<string, unknown>)
}

/** 项目表单实例详情 */
export function getProjectForm(id: SnowflakeId, projectFormId: SnowflakeId) {
  return get<ProjectFormVO>(`/project/${id}/form/${projectFormId}`)
}
```

### 6.2 `api/assistant.ts`（Phase K 流式）

```typescript
import type {SnowflakeId} from '@/types/api'
import type {AssistantChatRequest, AssistantChatResponse} from '@/types/assistant'

/** 流式回调集合：delta 增量 / final 完整响应 / error 失败消息 */
export interface AssistantStreamCallbacks {
  onDelta?: (delta: string) => void
  onFinal?: (response: AssistantChatResponse) => void
  onError?: (message: string) => void
}

/**
 * 项目助手流式对话（fetch + ReadableStream 解析 SSE；final 后 resolve，
 * error 事件/HTTP JSON 错误/连接中断均 reject；externalSignal 供组件卸载中断）
 */
export async function chatStream(
    projectId: SnowflakeId,
    data: AssistantChatRequest,
    callbacks: AssistantStreamCallbacks = {},
    externalSignal?: AbortSignal
): Promise<void>
```

### 6.3 既有 API 扩展

```typescript
// api/file.ts — uploadFile 增加可选 projectId
export function uploadFile(file: File, onProgress?: (p: number) => void, projectId?: SnowflakeId) {
  const formData = new FormData()
  formData.append('file', file)
  if (projectId) {
    formData.append('projectId', projectId)   // multipart 参数，字符串即可
  }
  return post<FileUploadVO>('/file/upload', formData, { /* 同现有 config */ })
}

// api/task.ts — TaskStartRequest 类型扩展（types/task.ts）
export interface TaskStartRequest {
  projectId?: SnowflakeId   // 新增可选；独立填报路径不传（字段缺省）
  formId: SnowflakeId
  fileId: SnowflakeId
}
```

---

## 7. 新增页面与路由设计

### 7.1 路由注册（`router/index.ts`）

```typescript
{ path: '/project/list',   name: 'ProjectList',   component: () => import('@/views/project/ProjectList.vue'),   meta: { title: '项目管理', icon: 'Folder' } },
{ path: '/project/create', name: 'ProjectCreate', component: () => import('@/views/project/ProjectCreate.vue'), meta: { title: '新建项目' } },
{ path: '/project/:id',    name: 'ProjectDetail', component: () => import('@/views/project/ProjectDetail.vue'), meta: { title: '项目详情' } }
```

同步在 `MainLayout.vue` 的 `menuItems` 登记 `{index: '/project/list', title: '项目管理', icon: 'Folder'}`
，并在 `activeMenu` 计算属性中增加 `/project` 前缀匹配。

### 7.2 页面清单

| 页面                                    | 路径                | 职责                                                                            |
|---------------------------------------|-------------------|-------------------------------------------------------------------------------|
| `views/project/ProjectList.vue`       | `/project/list`   | 分页列表（projectNo / projectName / 状态 / 时间）、新建入口、行点击进详情、删除二次确认（模式参照 FormList.vue） |
| `views/project/ProjectCreate.vue`     | `/project/create` | projectNo + projectName + description 表单（校验规则对齐后端：64/100/500 字符），成功后跳详情       |
| `views/project/ProjectDetail.vue`     | `/project/:id`    | **Tab 容器**：概览 / 文件 / 表单 / 智能助手                                                |
| `components/ProjectAssistantChat.vue` | —                 | 助手对话面板（§8），被详情页助手 Tab 引用                                                      |
| `components/ComparisonResult.vue`     | —                 | 比较结果卡片（§8），被 ChatPanel 引用                                                     |

### 7.3 项目详情 Tab 设计（Assistant 不设独立路由）

```
/project/1912345678901234567
├── Tab 概览：基础信息（el-descriptions：编号/名称/描述/状态/时间）+ 编辑/删除操作
├── Tab 文件：GET /project/{id}/files 正常分页表格
│            操作：关联已有文件（弹窗调 /file/page 选择）/ 解除关联 / 去上传（携带项目上下文跳 /file/upload）
├── Tab 表单：GET /project/{id}/forms 分页表格
│            操作：绑定表单（弹窗调 GET /form/page 选择）/ 查看实例详情
└── Tab 智能助手：<ProjectAssistantChat :project-id="..."/>（全高对话面板）
```

设计原则：**Project 是上下文，Assistant 是项目的一个能力**，与后端 `/project/{projectId}/assistant/chat`
的作用域一致；不做全局 `/project-assistant` 独立聊天页。

- Tab 切换用 `el-tabs`，`v-model` 同步 `route.query.tab`（概览缺省），刷新/分享后可还原 Tab 状态。
- **文件 Tab 必须用 `GET /project/{id}/files` 正常分页**；后端助手内部 `PageQuery(1,100)` 的文件清单仅服务于助手场景，两者不可混用（契约表
  #10）。
- **关联文件弹窗候选范围（交互规则）**：调 `GET /file/page`
  拉取候选后，前端必须过滤 `!f.projectId || String(f.projectId) === 当前项目ID`（`FileRecordVO.projectId` 已含归属信息，字符串序列化）——
  **排除已归属其他项目的文件**，避免用户选中后才收到 6003；过滤后为空时展示空态文案"无可关联文件（均已归属项目或列表为空）"
  。后端 6003 仍作为并发场景的最终兜底，前端不重复弹错。
- 详情页挂载时校验路由参数 `id` 为纯数字（参照 FormDetail.vue 的 `/^\d+$/` 校验），非法则提示并回列表。

---

## 8. 项目助手组件设计

### 8.1 消息流与 ChatMessage

```
用户发送 → messages.push({role:'user', content, timestamp})
        → messages.push({role:'assistant', content:'', loading:true})
        → chat(projectId, {conversationId, message})
        → 成功：填充 content=answer、response=res、loading=false
        → 失败：移除占位消息（错误已由拦截器提示）
```

- 渲染拆分：`<ChatMessageItem>`（气泡 + answer 富文本）+ `<StructuredFactsCard>`
  （冲突/正常卡片）+ `<ComparisonResult>` + `<ReferenceList>`（来源池）。
- `structuredData` 与 `comparisonData` 来自**最新一轮** assistant 消息的 `response`，随消息滚动定位。

### 8.2 conversationId 存储规则（硬性规则）

```typescript
// utils/assistantConversation.ts
const key = (projectId: string) => `aifp_assistant_conversation_${projectId}`

export function loadConversationId(projectId: string): string | null {
  return localStorage.getItem(key(projectId))
}
export function saveConversationId(projectId: string, conversationId: string): void {
  localStorage.setItem(key(projectId), conversationId)
}
```

- 进入助手 Tab：读 `aifp_assistant_conversation_${projectId}`；无则首轮请求**不携带**
  conversationId，响应回来后把 `res.conversationId` 存入。
- **禁止全局 key**（如 `aifp_conversation`）：后端会话隔离键为 `assistant:{projectId}:{conversationId}`
  ，前端必须同构按项目隔离，否则切项目后"那面积呢"这类指代会读到上一项目的历史。
- 切换项目即切换 key，天然隔离；提供"新会话"按钮：清空消息列表并删除当前 key（下轮由服务端发新 UUID）。

### 8.3 citation 渲染规则（硬性规则）

- `answer` + `citations` **共同渲染**：将 answer 按 `[S{n}]` / `[D{n}]` 标记分段，标记处渲染可点击上标角标；角标数据**只能
  **取自 `citations[]`（按 `citationId` 匹配）或回退 `references[]`。
- **禁止**解析 answer 后自建来源对象：前端只做"标记 → citations 成员"的显示锚点映射，不产生第二套 citation
  逻辑（避免与后端 `AnswerCitationParser` 的映射规则漂移）。
- 未匹配到 citations 成员的标记（后端已 warn 忽略，理论上不出现）：渲染为纯文本，不报错。
- 来源卡片展示：STRUCTURED → `rawValue + unit + sourceFileName + sourcePage`；FILE → `fileName + page`。`usedFiles`
  中的文件可点击跳转项目文件 Tab。

### 8.4 structuredData 冲突卡片（双视觉状态）

- `conflict=false`：正常表格行 / 卡片，展示 `rawValue + unit`，来源为"文件名 + 第 N 页"。
- `conflict=true`：**警告色冲突卡片**，完整列出 `values[]` 全部来源值（不得只展示一条），文案示例：

  > ⚠ 项目总投资存在多个来源值：施工许可证.pdf 第3页 = 10亿元；立项批复.pdf 第5页 = 12亿元。无法仅根据现有资料确认最终口径。

- 前端**不得**替用户挑选一个值作为事实（与后端"冲突不裁决"同构）。

### 8.5 ComparisonResult.vue（独立组件，只展示不计算）

- 输入：`CrossProjectComparisonVO`。输出：纯展示。
- 参与单元表：`rank` / `projectName` / `rawValue + unit` / `diffFromCurrent` / `diffFromCurrentPercent`。
- 聚合卡：`max / min / sum / avg / count`（后端 HALF_UP scale=4 结果直出）。
- 排除明细区：`excluded[]` 按原因中文映射展示（CONFLICT→数据冲突、UNPARSEABLE→值不可解析、UNIT_INCOMPATIBLE→单位不兼容），附全部来源值。
- **硬性规则**：组件内禁止 import 任何计算逻辑，禁止对 `normalizedValue` 做 reduce/sort/差值运算；`diffFromCurrent=null`
  的单元直接展示"—"（后端仅当前项目恰 1 个参与单元时计算）。

---

## 9. 现有页面改造点

### 9.1 FileUpload.vue

- **项目上下文进入**：项目文件 Tab"去上传"跳转 `/file/upload?projectId=xxx&projectName=yyy`；页面挂载时读取
  query，若有则展示醒目的归属条（如 `el-alert`）：`本项目上传的文件将自动关联项目：职教园一期`，并调 `getProject` 校验项目存在（防止失效
  ID）。
- **上传归属明示**：`handleUpload` 调用 `uploadFile(file, onProgress, projectId)`；上传成功结果卡中增加"所属项目"
  一行，防误认为未关联。
- **lastUpload 扩展**：`setLastUpload` 写入 `projectId/projectName`（独立上传路径字段缺省）。
- 独立上传路径（无 query.projectId）行为与现状完全一致。

### 9.2 AiFill.vue

- **projectId 透传**：项目上下文进入时（route query 或 store），`startTask({projectId, formId, fileId})`；独立填报路径
  projectId 为 undefined 时**字段缺省**（不传 `projectId: undefined`
  也兼容，但建议条件展开：`{...(projectId ? {projectId} : {}), formId, fileId}`）。
- **文件下拉按项目过滤（硬性规则）**：存在项目上下文时，文件数据源从 `getFileList` 改为 `getProjectFiles(projectId)`——*
  *只能选择当前项目文件**，防止在项目 A 中选中项目 B 的文件（后端 6004 会拒绝，但前端应在源头避免）。
- **lastUpload 预填防串项目**：读取 `getLastUpload()` 预填 fileId 前，校验 `lastUpload.projectId` 与当前上下文 projectId
  一致才预填；不一致则忽略预填。
- 表单下拉维持 `getFormList`（表单定义是全局资源，经 ProjectForm 实例与项目产生关系，此处不强制过滤）。
- 启动按钮校验增加：项目上下文内必须已选文件且文件来自项目列表。

---

## 10. 状态管理设计

新增 `stores/project.ts`（Pinia setup 风格，参照 stores/task.ts）：

```typescript
export const useProjectStore = defineStore('project', () => {
  const projectId = ref<SnowflakeId>('')
  const projectName = ref('')

  /** 设置当前项目上下文（进入项目详情/带项目跳转时调用） */
  function setProject(id: SnowflakeId, name: string) { projectId.value = id; projectName.value = name }
  /** 清空（离开项目上下文，如进入独立填报页） */
  function clear() { projectId.value = ''; projectName.value = '' }

  const hasContext = computed(() => !!projectId.value)
  return { projectId, projectName, hasContext, setProject, clear }
})
```

用途：项目详情 Tab 间共享上下文；FileUpload / AiFill 判定"是否处于项目模式"；避免逐层 props 透传。

---

## 11. 错误码与交互约定

业务错误统一由 Axios 拦截器 `ElMessage.error` 提示（沿用 frontend.md 约定），页面 catch
中只处理取消/静默。项目域错误码与文案（后端 `ResultCode`）：

| 错误码  | 含义        | 前端交互建议                             |
|------|-----------|------------------------------------|
| 6001 | 项目不存在     | 详情页展示 el-empty"项目不存在或已删除" + 返回列表按钮 |
| 6002 | 项目编号已存在   | 创建表单 projectNo 字段标红聚焦              |
| 6003 | 文件已关联其他项目 | 关联文件弹窗内提示，并刷新候选列表                  |
| 6004 | 文件不属于该项目  | 任务启动前的前端校验兜底，一般不触发                 |
| 6005 | 表单已绑定该项目  | 绑定表单弹窗内提示，刷新已绑定列表                  |
| 6006 | 项目表单不存在   | 表单 Tab 刷新列表                        |

403（无权限访问项目）：由后端 Service 层 `ProjectAccessService` 守门，前端按拦截器提示处理；详情页同样回退 el-empty。

---

## 12. 开发阶段划分

每阶段完成后**输出变更、说明设计、自验通过后等待确认**，再进入下一阶段。

| 阶段                     | 内容                                                                                                                                                                                                                                 | 产出/修改文件                     | 自验点                                                                                |
|------------------------|------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|-----------------------------|------------------------------------------------------------------------------------|
| **Phase 0**<br>前后端契约修复 | 后端：新增 `dto/IdVO.java`（`id` 字段 + `@JsonSerialize(ToStringSerializer)`）；`ProjectController#create`、`#bindForm` 返回类型 `Result<Long>` → `Result<IdVO>`。前端：`types/api.ts` 导出 `SnowflakeId` 类型别名与 `IdVO` 接口（API 层取 `.id` 映射规则见 §5.1/§6.1） | 后端 3 个文件；前端 types/api.ts    | `npm run type-check` 通过；后端现有测试全绿；Swagger/curl 验证两接口返回 `{"id":"1912..."}` 字符串（非裸数字） |
| **A**<br>Project CRUD  | types/project.ts + api/project.ts（CRUD 部分）+ ProjectList.vue + ProjectCreate.vue + 路由/菜单                                                                                                                                            | 新增 4 文件，改 router/MainLayout | 列表分页、创建回显、编辑（projectNo 禁改）、删除二次确认；错误码 6002 触发时字段标红                                 |
| **B**<br>文件/表单关联       | ProjectDetail.vue（概览/文件/表单三个 Tab）+ 关联/绑定弹窗                                                                                                                                                                                         | 新增 ProjectDetail.vue        | 文件 Tab 正常分页；关联幂等；6003/6005 提示正确                                                    |
| **C**<br>上传/任务联动       | FileUpload 改造（项目上下文 + lastUpload 扩展）+ AiFill 改造（projectId 透传 + 文件下拉按项目过滤 + 预填防串项目）+ utils/lastUpload.ts 扩展                                                                                                                         | 改 3 文件                      | 项目内上传后文件出现在项目文件 Tab；项目内填报文件下拉无跨项目文件；独立路径行为与现状一致（回归）                                |
| **D**<br>Assistant 基础  | types/assistant.ts（DTO + ChatMessage）+ api/assistant.ts + utils/assistantConversation.ts + ProjectAssistantChat.vue（消息流 + answer 纯文本渲染）                                                                                            | 新增 4 文件                     | conversationId 按项目隔离（localStorage key 抽查）；新会话/历史续聊正常；离开 Tab 不泄漏状态                  |
| **E**<br>引用/冲突/比较      | answer 标记锚点渲染 + ReferenceList + StructuredFactsCard（双视觉）+ ComparisonResult.vue                                                                                                                                                     | 改 ChatPanel，新增 2 组件         | `[S1]`/`[D1]` 角标点击弹出对应 citations 成员；冲突卡片列出全部来源值；比较数据与后端计算一致（前端零计算）                 |
| **F**<br>全量回归          | 按本文档 §13 清单 + frontend.md §15 约定逐项回归                                                                                                                                                                                               | —                           | 表单/上传/填报旧功能无回归；`npm run build` 通过                                                  |

---

## 13. 验证清单

- [ ] 契约表（§3）10 项逐条核对：裸 Long 两项已在 Phase 0 修复并验证返回字符串
- [ ] IdVO 映射：`createProject` / `bindProjectForm` 对外返回 `SnowflakeId`（API 层已取 `.id`），页面无 `{id}` 对象泄漏
- [ ] 关联文件弹窗候选已按 `projectId` 过滤（排除已归属其他项目的文件），空态有明确文案
- [ ] API 表（§4）与 `ProjectController`（11
  端点：create/get/page/update/delete/files/associate/dissociate/bindForm/forms/formDetail）、`ProjectAssistantController`
  （1 端点）、`FileController#upload`、`TaskStartRequest` 字段逐一对照
- [ ] 类型字段（§5）与后端 DTO/VO 源码抽查：ProjectVO / ProjectFormVO / AssistantChatResponse / StructuredFieldFact /
  CrossProjectComparisonVO
- [ ] SnowflakeId：新模块无裸 `number` ID、无 `number | string` 联合类型
- [ ] conversationId：localStorage key 为 `aifp_assistant_conversation_${projectId}`，无全局 key
- [ ] citation：answer 标记仅作锚点，来源对象全部取自 citations/references
- [ ] structuredData：conflict=true 时展示全部来源值，无前端裁决
- [ ] comparisonData：前端零计算，excluded 有原因映射展示
- [ ] 项目文件 Tab 走 `/project/{id}/files` 分页，与助手 100 条清单无混用
- [ ] AiFill 项目模式文件下拉仅含当前项目文件；lastUpload 预填校验 projectId 一致
- [ ] 独立填报/上传路径（无项目上下文）行为与改造前一致（旧功能无回归）
- [ ] `npm run type-check` 与 `npm run build` 通过

---

## 14. 后端接口覆盖度对照（Phase I 审计）

> 审计基准：后端 7 个 Controller 共 **23 个端点** vs `web/src/api/*.ts` 全量函数 + 页面消费。
> 结论：**前端已对接 21 个；未对接 2 个，经确认均不对接**（理由见 14.2）。

### 14.1 已对接端点全量对照（21 / 23）

| Controller                 | 方法 + 路径                                  | 前端 API 函数                             | 消费页面/组件                                              |
|----------------------------|------------------------------------------|---------------------------------------|------------------------------------------------------|
| FormController             | POST /form/create                        | `createForm`（api/form.ts）             | FormCreate                                           |
| FormController             | GET /form/page                           | `getFormList`                         | FormList、ProjectDetail 绑定表单弹窗                        |
| FormController             | GET /form/{id}                           | `getFormDetail`                       | FormDetail、FormList 按 ID 查询、ProjectBindDialog（表单上下文） |
| FormController             | POST /form/{id}/field                    | `addField`                            | FormDetail                                           |
| FormController             | DELETE /form/{id}/field/{fieldId}        | `deleteField`                         | FormDetail                                           |
| FormController             | DELETE /form/{id}                        | `deleteForm`                          | FormList                                             |
| ProjectController          | POST /project                            | `createProject`（取 `.id`）              | ProjectCreate                                        |
| ProjectController          | GET /project/page                        | `getProjectPage`                      | ProjectList、ProjectBindDialog 候选                     |
| ProjectController          | GET /project/{id}                        | `getProject`                          | ProjectDetail                                        |
| ProjectController          | PUT /project/{id}                        | `updateProject`                       | ProjectDetail 编辑                                     |
| ProjectController          | DELETE /project/{id}                     | `deleteProject`                       | ProjectList / ProjectDetail 删除                       |
| ProjectController          | GET /project/{id}/files                  | `getProjectFiles`                     | ProjectDetail 文件 Tab、AiFill 项目文件下拉                   |
| ProjectController          | POST /project/{id}/file/{fileId}         | `associateFile`                       | ProjectDetail 关联弹窗、FileUpload 绑定（Phase H）            |
| ProjectController          | DELETE /project/{id}/file/{fileId}       | `dissociateFile`                      | ProjectDetail 文件 Tab                                 |
| ProjectController          | POST /project/{id}/form                  | `bindProjectForm`（取 `.id`）            | ProjectDetail 绑定弹窗、ProjectBindDialog（form 模式）        |
| ProjectController          | GET /project/{id}/forms                  | `getProjectForms`                     | ProjectDetail 表单 Tab、AiFill 项目表单下拉                   |
| ProjectController          | GET /project/{id}/form/{projectFormId}   | `getProjectForm`                      | ProjectDetail 表单实例详情                                 |
| FileController             | POST /file/upload                        | `uploadFile`                          | FileUpload                                           |
| FileController             | GET /file/page                           | `getFileList`                         | FileUpload 全部文件列表、ProjectDetail 关联弹窗候选               |
| TaskController             | POST /task                               | `startTask`                           | AiFill                                               |
| TaskController             | GET /task/progress/{taskId}（SSE）         | `subscribeTaskProgress`（utils/sse.ts） | AiFill                                               |
| ProjectAssistantController | POST /project/{projectId}/assistant/chat | `chat`（api/assistant.ts）              | ProjectAssistantChat                                 |

**分组小计（准确口径）**：

| Controller                 | 端点数    | 已对接    |
|----------------------------|--------|--------|
| FormController             | 6      | 6      |
| ProjectController          | 11     | 11     |
| FileController             | 2      | 2      |
| TaskController             | 2      | 2      |
| ProjectAssistantController | 1      | 1      |
| HealthController           | 1      | 0      |
| FillController             | 1      | 0      |
| **合计**                     | **24** | **22** |

### 14.2 未对接端点处置（2 个，均不对接）

| 端点                                               | 处置                   | 理由                                                                                                                                                                 |
|--------------------------------------------------|----------------------|--------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| GET /health/ping（HealthController）               | 不对接                  | 运维健康检查接口，无前端使用场景                                                                                                                                                   |
| POST /fill/{formId}?fileId=（FillController，同步抽取） | **不对接（Phase I 已确认）** | 与 POST /task 异步流调用同一 `FieldExtractorService.extract`，返回同构 `ExtractionResult`，能力完全重复；异步流已具备进度推送与结果元数据展示（errors / attemptsUsed 均已消费）；同步接口无进度且受 axios 30s 超时约束，长文档易超时 |

**再评估触发条件**：若未来出现"小文档快速试抽取"场景（跳过任务流直接验证抽取效果），可为该请求单独配置更长超时（如
120s）后再对接，其余场景维持异步流。

### 14.3 数据消费核查（无缺口）

| 后端返回数据                                   | 前端消费点                       |
|------------------------------------------|-----------------------------|
| `ExtractionResult.errors`                | AiFill 结果区错误列表（errorRows）   |
| `ExtractionResult.attemptsUsed`          | AiFill 结果区"重试次数"Tag         |
| `TaskProgress.message`                   | AiFill 进度文案 / 失败 errorMsg   |
| `ProjectFormVO.version` / `sourceFileId` | ProjectDetail 表单 Tab 列与实例详情 |

### 14.4 新增后端接口的对接规范

1. **API 层**：在 `web/src/api/<模块>.ts` 新增函数；URL 与后端 `@RequestMapping` 逐一对照，返回类型标注后端 VO。
2. **类型层**：`types/*.ts` 与后端 DTO/VO 字段逐一对照；雪花 ID 一律 `SnowflakeId`（字符串）；`Result<IdVO>` 型接口在 API
   层取 `.id` 后返回（页面只接触字符串 ID）。
3. **错误码**：新错误码默认由 Axios 拦截器统一提示；需要前置校验/兜底交互的（如唯一性冲突、归属冲突），按 §7.3
   关联弹窗模式做候选过滤 + 拦截器兜底，并同步登记 §11 错误码表。
4. **SSE 类接口**：沿用 `utils/sse.ts` 封装模式（终态/异常自动关闭 + `onUnmounted` 清理）。
5. **验收**：`npm run type-check` + `npm run build` 通过；走查覆盖新错误码触发路径与旧功能回归。
