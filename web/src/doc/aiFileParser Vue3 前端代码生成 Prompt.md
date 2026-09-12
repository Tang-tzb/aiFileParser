根据你现在项目上下文里的 **Controller 实际接口**
，前端不能再按之前“假想接口”设计，而应该严格按照现有后端对接。当前后端已经有 `FileController、FillController、FormController、TaskController`
，统一返回 `Result`，并且异步解析通过 SSE 推送进度；`HealthController` 仅用于后端健康检查，不需要前端页面对接。

下面这份 Prompt 可以直接复制给 Cursor / Trae / Claude Code / ChatGPT Agent，让它生成 Vue3 前端。

# aiFileParser Vue3 前端代码生成 Prompt

你是一名资深 Vue3 前端架构师，请根据我提供的后端项目上下文，为现有 `aiFileParser` 后端开发一个完整的企业级 Vue3 前端。

## 一、项目目标

这是一个：

> AI 文件读取自动填报表单系统

业务流程：

```text
用户创建动态表单
        ↓
配置表单字段
        ↓
上传 PDF / Excel / Word
        ↓
后端解析文件
        ↓
文档切片 + 向量化
        ↓
AI 根据表单字段提取数据
        ↓
返回结构化字段
        ↓
前端自动填充表单
        ↓
用户人工确认 / 修改
```

前端必须围绕以下核心页面设计：

1. 表单管理
2. 创建/编辑动态表单
3. 表单字段配置
4. 文件上传
5. AI 自动填报
6. 异步解析任务
7. SSE 实时进度
8. AI 提取结果展示
9. 人工确认和修改

------

# 二、前端技术栈

使用：

- Vue 3
- Vite
- TypeScript
- Element Plus
- Vue Router
- Pinia
- Axios
- SCSS

要求：

- 使用 ``
- 使用 Composition API
- 所有 API 使用 TypeScript 类型定义
- 不使用 Options API
- 不使用 JQuery
- 不使用 Vue2 写法

推荐目录：

```text
src/
├── api/
│   ├── http.ts
│   ├── form.ts
│   ├── file.ts
│   ├── fill.ts
│   └── task.ts
│
├── components/
│   ├── DynamicForm.vue
│   ├── FormFieldEditor.vue
│   ├── FileUploader.vue
│   ├── ParseProgress.vue
│   └── ExtractionResult.vue
│
├── views/
│   ├── form/
│   │   ├── FormList.vue
│   │   ├── FormCreate.vue
│   │   └── FormDetail.vue
│   │
│   ├── file/
│   │   └── FileUpload.vue
│   │
│   └── fill/
│       └── AiFill.vue
│
├── router/
│   └── index.ts
│
├── stores/
│   └── task.ts
│
├── types/
│   ├── api.ts
│   ├── form.ts
│   ├── file.ts
│   └── task.ts
│
├── utils/
│   └── request.ts
│
├── App.vue
└── main.ts
```

------

# 三、后端统一返回结构

后端所有 Controller 均使用：

```typescript
interface Result<T> {
  code: number
  message: string
  data: T
  timestamp: number
}
```

前端 Axios 封装必须统一处理：

```text
HTTP状态码正常
        ↓
检查 result.code
        ↓
code === 200
        ↓
返回 result.data
```

如果业务错误：

```text
code !== 200
        ↓
ElMessage.error(message)
```

不要在每个页面重复处理。

------

# 四、后端 context-path

后端：

```text
/aifp
```

端口：

```text
8080
```

前端开发环境 API Base URL：

```text
http://localhost:8080/aifp
```

通过 Vite proxy 解决跨域。

最终：

```text
/api
  ↓
http://localhost:8080/aifp
```

Axios 不要把 `/aifp` 到处硬编码。

统一：

```typescript
const request = axios.create({
  baseURL: '/api'
})
```

Vite：

```text
/api
→
http://localhost:8080/aifp
```

------

# 五、Controller 接口必须严格按照后端现有实现

不要自行发明接口。

当前需要对接：

```text
FileController
FormController
FillController
TaskController
```

以下 Controller 不需要前端页面：

```text
HealthController
```

------

# 六、表单模块

## 1. 创建表单

后端：

```http
POST /form/create
```

完整地址：

```http
POST /aifp/form/create
```

请求：

```typescript
interface FormCreateDTO {
  formName: string
  description?: string
  fields?: FormFieldCreateDTO[]
}
```

字段：

```typescript
interface FormFieldCreateDTO {
  fieldName: string
  fieldCode: string
  fieldType: FieldType
  required?: boolean
  description?: string
  sort?: number
}
```

字段类型：

```typescript
enum FieldType {
  STRING = 'STRING',
  INTEGER = 'INTEGER',
  DECIMAL = 'DECIMAL',
  DATE = 'DATE',
  BOOLEAN = 'BOOLEAN'
}
```

------

# 七、动态表单设计页面

页面：

```text
/form/create
```

设计一个可视化动态表单编辑器。

页面布局：

```text
------------------------------------------------
| 表单基本信息                                   |
|                                              |
| 表单名称：[____________________]              |
| 描述：[________________________]               |
------------------------------------------------

------------------------------------------------
| 字段配置                         [+ 添加字段]   |
------------------------------------------------

| 排序 | 字段名称 | 字段编码 | 类型 | 必填 | 操作 |
------------------------------------------------
|  1  | 项目名称 | projectName | 字符串 | 是 | 编辑 删除 |
|  2  | 投资金额 | amount      | 小数   | 是 | 编辑 删除 |
------------------------------------------------

                    [保存表单]
```

添加字段时使用 Element Plus Dialog。

字段支持：

```text
STRING
INTEGER
DECIMAL
DATE
BOOLEAN
```

字段配置需要包含：

```text
字段名称
字段编码
字段类型
是否必填
字段描述
排序
```

字段编码必须符合：

```regex
^[a-zA-Z][a-zA-Z0-9_]*$
```

并且在当前表单内唯一。

------

# 八、查询表单

后端：

```http
GET /form/{id}
```

返回：

```typescript
interface FormVO {
  formId: number
  formName: string
  description?: string
  createTime: string
  updateTime: string
  fields: FormFieldVO[]
}
```

字段：

```typescript
interface FormFieldVO {
  fieldId: number
  fieldName: string
  fieldCode: string
  fieldType: FieldType
  required: boolean
  description?: string
  sort: number
}
```

前端：

```text
GET /form/1
```

用于：

- 表单详情
- AI填报页面
- 动态渲染表单
- 字段展示

------

# 九、单独添加字段

后端：

```http
POST /form/{id}/field
```

前端创建字段时允许：

```text
当前表单已经保存
        ↓
继续添加字段
```

调用该接口。

------

# 十、删除字段

后端：

```http
DELETE /form/{id}/field/{fieldId}
```

前端删除前弹出：

```text
确定删除字段“投资金额”吗？
```

确认后调用 API。

------

# 十一、文件上传模块

后端：

```http
POST /file/upload
```

参数：

```text
multipart/form-data

file
```

支持：

```text
PDF
Excel
Word
TXT
```

后端 FileType 当前支持：

```text
PDF
EXCEL
WORD
TXT
OTHER
```

前端使用：

```text
Element Plus Upload
```

但不要直接依赖组件默认上传行为。

自己控制：

```typescript
FormData
```

然后调用 Axios。

------

# 十二、文件上传页面

页面：

```text
/file/upload
```

UI：

```text
------------------------------------------

        AI 文件自动解析

  支持 PDF / Excel / Word / TXT

      [ 点击上传 / 拖拽文件 ]

------------------------------------------

文件：

项目申报书.pdf

状态：
上传成功

File ID：
10001

------------------------------------------
```

上传成功后保存：

```typescript
interface FileUploadVO {
  fileId: number
  fileName: string
  fileType: string
  filePath: string
  status: string
  createTime: string
}
```

上传成功之后：

```text
自动保存 fileId
```

后续 AI 填报必须使用：

```text
fileId
```

------

# 十三、AI 自动填报页面

页面：

```text
/fill
```

页面布局：

```text
--------------------------------------------------

选择表单：

[ 项目申报表 ▼ ]

--------------------------------------------------

上传文件：

[ 项目申报书.pdf ]

--------------------------------------------------

             [开始AI解析]

--------------------------------------------------
```

选择：

```text
formId
fileId
```

然后进入异步解析。

------

# 十四、异步任务接口

后端：

```http
POST /task
```

请求：

```typescript
interface TaskStartRequest {
  formId: number
  fileId: number
}
```

返回：

```typescript
interface TaskStartVO {
  taskId: string
  fileId: number
  formId: number
  createTime: string
}
```

前端开始任务后：

```text
POST /task

↓

拿到 taskId

↓

立即建立 SSE

↓

监听解析进度
```

注意：

不要在 POST /task 返回后轮询。

后端已经设计了：

```text
Redis + SSE
```

前端必须优先使用 SSE。

------

# 十五、SSE 进度接口

后端：

```http
GET /task/progress/{taskId}
```

完整：

```http
GET /aifp/task/progress/{taskId}
```

响应：

```text
text/event-stream
```

前端使用：

```typescript
EventSource
```

或者封装自己的 SSE 工具。

SSE 不要使用 Axios。

------

# 十六、TaskProgress 数据结构

后端：

```typescript
interface TaskProgress {
  taskId: string
  fileId: number
  formId: number
  status: string
  percent: number
  message: string
  result?: ExtractionResult
  timestamp: number
}
```

状态：

```text
PARSING
VECTORING
EXTRACTING
SUCCESS
FAILED
```

进度：

```text
0
50
80
100
```

失败：

```text
percent = -1
status = FAILED
```

------

# 十七、SSE 前端显示

UI：

```text
AI文件解析

[██████████████------] 80%

当前阶段：

正在进行 AI 字段提取...

PARSING
✓

VECTORING
✓

EXTRACTING
●

SUCCESS
○
```

建议使用：

```text
El Steps
+
El Progress
```

组合。

------

# 十八、SSE 状态转换

前端：

```text
PARSING
→
VECTORING
→
EXTRACTING
→
SUCCESS
```

失败：

```text
任意阶段
→
FAILED
```

成功：

```text
percent = 100
```

立即：

```text
关闭 EventSource
```

失败也关闭。

避免连接泄漏。

------

# 十九、SSE 断线重连

后端已经在 Redis 保存：

```text
task:progress:{taskId}
```

前端需要考虑：

```text
浏览器刷新
网络断开
页面重新进入
```

重新进入任务页面时：

```text
重新创建 EventSource
```

后端会推送快照。

不要自行写轮询。

------

# 二十、AI解析结果

后端：

```http
POST /fill/{formId}?fileId={fileId}
```

同步接口。

返回：

```typescript
interface ExtractionResult {
  values: Record<string, any>
  errors: FieldError[]
  attemptsUsed: number
}
```

FieldError：

```typescript
interface FieldError {
  fieldCode: string
  errorType: string
  message: string
  rawValue: any
}
```

异步任务成功时：

```text
TaskProgress.result
```

里面同样携带：

```text
ExtractionResult
```

------

# 二十一、自动填表页面

SSE 成功：

```text
TaskProgress.status === SUCCESS
```

获取：

```text
result.values
```

然后：

```text
根据 form.fields
动态生成表单
```

例如：

表单字段：

```text
projectName
amount
company
startDate
```

AI：

```json
{
  "projectName": "智慧校园建设",
  "amount": 5000000,
  "company": "XX大学",
  "startDate": "2026-01-01"
}
```

前端自动：

```text
项目名称：[智慧校园建设]
投资金额：[5000000]
建设单位：[XX大学]
开始时间：[2026-01-01]
```

------

# 二十二、动态表单渲染

必须根据：

```text
fieldType
```

自动决定组件。

映射：

```text
STRING
→
el-input

INTEGER
→
el-input-number

DECIMAL
→
el-input-number

DATE
→
el-date-picker

BOOLEAN
→
el-switch
```

不要把字段写死。

例如：

错误：

```vue
<input v-model="projectName">
```

正确：

```text
遍历 form.fields

↓

根据 fieldType

↓

动态组件
```

------

# 二十三、人工确认机制

AI结果不能直接认为100%正确。

页面需要明确显示：

```text
AI解析结果

----------------------------------

项目名称
[ 智慧校园建设                ]

投资金额
[ 5000000                    ]

建设单位
[ XX大学                      ]

开始日期
[ 2026-01-01                  ]

----------------------------------

AI识别成功：

✓ 5个字段

需要人工确认：

! 1个字段

----------------------------------

[重新解析]       [确认填报]
```

有错误：

```text
字段错误
```

使用：

```text
El Alert
/
El Tag
```

展示。

------

# 二十四、错误字段展示

例如：

```typescript
FieldError {
  fieldCode: "amount",
  errorType: "TYPE",
  message: "无法转换为DECIMAL",
  rawValue: "约500万元"
}
```

前端：

```text
投资金额

⚠ AI识别：

约500万元

请人工确认
```

不要因为部分字段失败而直接丢弃全部结果。

------

# 二十五、AI解析重新执行

在结果页面提供：

```text
[重新AI解析]
```

重新创建任务：

```text
POST /task
```

不要自己调用：

```text
POST /fill
```

进行长时间同步处理。

异步任务是主推荐流程。

------

# 二十六、页面路由

配置：

```text
/
├── /form
│   ├── list
│   ├── create
│   └── :id
│
├── /file
│   └── upload
│
└── /fill
    └── index
```

建议：

```text
/ → 跳转 /form
```

------

# 二十七、页面整体布局

使用企业后台布局：

```text
---------------------------------------------------
| Logo | AI文件自动填报系统                       |
---------------------------------------------------
|       |                                         |
| 菜单  |              页面内容                    |
|       |                                         |
| 表单管理 |                                       |
| 文件上传 |                                       |
| AI填报 |                                         |
|       |                                         |
---------------------------------------------------
```

左侧菜单：

```text
表单管理
文件上传
AI自动填报
```

`HealthController` 不生成菜单、不生成页面。

------

# 二十八、API模块设计

请生成：

```text
src/api/form.ts

src/api/file.ts

src/api/fill.ts

src/api/task.ts
```

例如：

```typescript
export function createForm(data: FormCreateDTO) {
  return request.post<FormCreateResult>(
    '/form/create',
    data
  )
}
```

上传：

```typescript
export function uploadFile(file: File) {
  const formData = new FormData()
  formData.append('file', file)

  return request.post<FileUploadVO>(
    '/file/upload',
    formData
  )
}
```

任务：

```typescript
export function startTask(data: TaskStartRequest) {
  return request.post<TaskStartVO>(
    '/task',
    data
  )
}
```

------

# 二十九、SSE工具设计

不要把 SSE 逻辑写死在 Vue 页面里面。

设计：

```text
src/utils/sse.ts
```

提供：

```typescript
createTaskSse(taskId, options)
```

支持：

```text
onMessage
onError
onOpen
close
```

示例：

```typescript
const sse = createTaskSse(taskId, {
  onMessage(progress) {
    ...
  },
  onError(error) {
    ...
  }
})
```

组件卸载：

```typescript
onBeforeUnmount(() => {
  sse.close()
})
```

------

# 三十、Pinia任务状态管理

创建：

```text
stores/task.ts
```

保存：

```text
currentTaskId
status
percent
message
result
```

这样：

```text
AI填报页面
刷新
重新进入
```

状态管理更加清晰。

------

# 三十一、UI交互要求

所有异步操作：

```text
loading
```

都必须有状态。

例如：

创建表单：

```text
保存中...
```

文件上传：

```text
上传中...
```

AI任务：

```text
解析中...
```

AI完成：

```text
解析完成
```

失败：

```text
解析失败
```

------

# 三十二、错误处理

Axios：

```text
网络错误
服务器错误
业务错误
```

统一处理。

业务错误：

```typescript
if (response.data.code !== 200) {
  ElMessage.error(response.data.message)
}
```

不要在每个页面重复判断。

------

# 三十三、TypeScript类型要求

必须为以下对象建立类型：

```text
Result<T>

FormCreateDTO

FormFieldCreateDTO

FormVO

FormFieldVO

FileUploadVO

TaskStartRequest

TaskStartVO

TaskProgress

ExtractionResult

FieldError
```

不允许：

```typescript
any
```

大量泛滥。

对于动态表单 values 可以：

```typescript
Record<string, unknown>
```

然后根据：

```text
FieldType
```

进行转换。

------

# 三十四、动态字段校验

前端必须根据：

```text
required
fieldType
```

动态创建 Element Plus FormRules。

例如：

```text
required=true
```

自动：

```text
required校验
```

类型：

```text
INTEGER
```

限制整数。

类型：

```text
DECIMAL
```

允许数字。

------

# 三十五、开发顺序

不要一次生成全部前端代码。

按照以下阶段开发：

## Phase 1

创建：

```text
Vue3 + Vite + TypeScript
```

完成：

- Router
- Pinia
- Element Plus
- Axios
- 基础布局

------

## Phase 2

实现：

```text
表单管理
```

包括：

- 表单列表
- 创建表单
- 动态字段配置
- 字段删除
- 表单详情

------

## Phase 3

实现：

```text
文件上传
```

包括：

- 文件选择
- 上传
- 上传结果
- fileId保存

------

## Phase 4

实现：

```text
AI自动填报
```

包括：

- 选择表单
- 选择文件
- 开始任务

------

## Phase 5

实现：

```text
SSE任务进度
```

包括：

- EventSource
- 进度条
- 阶段状态
- 错误处理
- 自动关闭连接

------

## Phase 6

实现：

```text
动态表单自动填充
```

包括：

- 根据FieldType渲染
- AI values映射
- 错误字段展示
- 人工修改

------

## Phase 7

实现：

```text
企业级UI优化
```

包括：

- loading
- empty
- error
- responsive
- 用户体验
- 统一风格

------

# 三十六、代码质量要求

必须遵守：

1. Vue组件职责单一
2. 不允许一个Vue文件超过500行
3. 复杂逻辑拆成 composable
4. API全部独立
5. SSE独立工具
6. Pinia独立管理任务状态
7. 类型全部TypeScript化
8. 不在页面中硬编码API地址
9. 不在页面中重复处理Result
10. 不生成无用Demo代码

------

# 三十七、最终运行要求

前端：

```bash
npm install
npm run dev
```

后端：

```text
localhost:8080/aifp
```

前端：

```text
localhost:5173
```

Vite代理：

```text
/api/*
↓
http://localhost:8080/aifp/*
```

最终完整流程：

```text
进入系统

↓

创建表单

↓

添加字段

↓

保存

↓

上传项目文件

↓

获得 fileId

↓

进入AI自动填报

↓

选择formId + fileId

↓

POST /task

↓

获得taskId

↓

建立SSE

↓

PARSING 0%

↓

VECTORING 50%

↓

EXTRACTING 80%

↓

SUCCESS 100%

↓

获取ExtractionResult

↓

动态生成表单

↓

自动填充values

↓

错误字段提示

↓

人工检查

↓

完成填报
```

## 特别注意

### 不需要对接：

```text
GET /health/ping
```

`HealthController` 只作为后端健康检查接口，不生成：

- API页面
- 菜单
- Store
- Router
- UI

```
FillController 也不要生成和对接
```

### 必须严格按照现有 Controller 和 DTO 对接

不要自行修改后端接口，不要自行增加不存在的后端API。

如果发现接口定义与当前前端需求不一致：

1. 先指出不一致
2. 根据现有接口完成前端
3. 不要擅自修改后端

------

# 最终交付

完成后输出：

```text
1. 完整src目录

2. package.json

3. vite.config.ts

4. tsconfig.json

5. README.md

6. 前端启动方式

7. 后端接口对接说明

8. 页面访问路径

9. SSE连接说明
```

并且：

- 每个阶段完成后停止
- 展示修改的文件
- 展示核心代码
- 说明运行方式
- 等待确认后再进入下一阶段