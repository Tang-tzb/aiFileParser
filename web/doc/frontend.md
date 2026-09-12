# AI 文件自动填报系统 — 前端代码文档

> 版本：v0.1.0 ｜ 更新日期：2026-09-12
> 本文档基于 `web/` 目录现有代码结构编写，供后续开发与维护参考。

---

## 1. 项目概述

前端是「AI 文件自动填报系统」的单页应用（SPA），提供三大核心能力：

| 功能模块    | 路由                                      | 说明                                      |
|---------|-----------------------------------------|-----------------------------------------|
| 表单管理    | `/form/list`、`/form/create`、`/form/:id` | 表单定义（含字段配置）的增删查                         |
| 文件上传    | `/file/upload`                          | PDF / Excel / Word / TXT 单文件上传，带进度条     |
| AI 自动填报 | `/fill/index`                           | 选择表单 + 文件启动异步解析任务，SSE 实时推送进度，展示 AI 抽取结果 |

技术栈：**Vue 3（`<script setup>` 组合式 API）+ TypeScript + Vite + Element Plus + Pinia + Vue Router + Axios + 原生
EventSource（SSE）**。

---

## 2. 目录结构

```
web/
├── doc/                        # 项目文档（本文件）
├── src/
│   ├── main.ts                 # 应用入口：初始化 Vue / Pinia / Router / Element Plus
│   ├── App.vue                 # 根组件（仅渲染 <router-view>）
│   ├── env.d.ts                # Vite 环境类型声明
│   ├── router/
│   │   └── index.ts            # 路由配置（MainLayout + 3 个业务页 + 404）
│   ├── layouts/
│   │   └── MainLayout.vue      # 主布局：Header + Sidebar + 主内容区
│   ├── views/                  # 页面级组件
│   │   ├── form/
│   │   │   ├── FormList.vue    # 表单管理列表（分页 / ID 查询 / 删除）
│   │   │   ├── FormCreate.vue  # 新建表单（表单头 + 动态字段配置）
│   │   │   └── FormDetail.vue  # 表单详情（查看字段 / 追加字段 / 删除字段）
│   │   ├── file/
│   │   │   └── FileUpload.vue  # 文件上传（拖拽上传 + 进度条 + 结果展示）
│   │   ├── fill/
│   │   │   └── AiFill.vue      # AI 自动填报（任务启动 + SSE 进度 + 结果展示）
│   │   └── NotFound.vue        # 404 页面
│   ├── components/
│   │   └── FormFieldEditor.vue # 可复用字段编辑器 Dialog（新增/编辑字段）
│   ├── api/                    # 接口层：按后端 Controller 划分
│   │   ├── http.ts             # get/post/put/del 通用请求方法
│   │   ├── form.ts             # 表单模块 API（FormController）
│   │   ├── file.ts             # 文件模块 API（FileController）
│   │   └── task.ts             # 任务模块 API（TaskController）
│   ├── utils/
│   │   ├── request.ts          # Axios 实例 + 拦截器（统一拆解 Result / 错误提示）
│   │   ├── sse.ts              # SSE 订阅封装（EventSource）
│   │   └── lastUpload.ts       # 最近上传记录的 localStorage 持久化
│   ├── stores/
│   │   └── task.ts             # Pinia store：异步任务进度状态
│   ├── types/                  # 与后端 DTO/VO 对齐的类型定义
│   │   ├── api.ts              # Result<T> / PageResult<T> / 成功码常量
│   │   ├── form.ts             # FieldType / FormCreateDTO / FormVO 等
│   │   ├── file.ts             # FileType / FileStatus / FileUploadVO 等
│   │   └── task.ts             # TaskStatus / TaskStartVO / TaskProgress 等
│   └── styles/
│       └── index.scss          # 全局样式与 CSS 变量
├── index.html                  # HTML 入口
├── vite.config.ts              # Vite 配置（代理 / Element Plus 自动导入）
├── package.json                # 依赖与脚本
└── tsconfig.json / tsconfig.node.json
```

---

## 3. 应用启动流程

`src/main.ts` 初始化顺序：

```typescript
const app = createApp(App)
// 全局注册所有 Element Plus 图标组件
for (const [key, component] of Object.entries(ElementPlusIconsVue)) {
  app.component(key, component)
}
app.use(createPinia()).use(router).use(ElementPlus).mount('#app')
```

- 全量引入 Element Plus 样式并注册图标（图标在模板中可直接用 `<Document/>` 等）。
- `unplugin-auto-import` / `unplugin-vue-components` 在 `vite.config.ts` 中配置 Element Plus 按需自动导入（API
  与组件），`auto-imports.d.ts`、`components.d.ts` 为自动生成文件，勿手改。

---

## 4. 路由设计

`src/router/index.ts`：

| 路径                 | 名称         | 组件                                 | 菜单                       |
|--------------------|------------|------------------------------------|--------------------------|
| `/`                | —          | MainLayout，redirect → `/form/list` | —                        |
| `/form/list`       | FormList   | `views/form/FormList.vue`          | 表单管理（icon: Document）     |
| `/file/upload`     | FileUpload | `views/file/FileUpload.vue`        | 文件上传（icon: UploadFilled） |
| `/fill/index`      | AiFill     | `views/fill/AiFill.vue`            | AI自动填报（icon: MagicStick） |
| `/:pathMatch(.*)*` | NotFound   | `views/NotFound.vue`               | 不在菜单                     |

约定：

- 业务页面均挂在 MainLayout 下；`meta.title` / `meta.icon` 供侧边栏取用。
- 新增页面时需同步在 `MainLayout.vue` 的 `menuItems` 数组中登记菜单项。

---

## 5. HTTP 请求层

### 5.1 Axios 实例（`utils/request.ts`）

- `baseURL: '/api'`，超时 30s，默认 `Content-Type: application/json`。
- **请求拦截器**：预留 token 注入位置（当前无鉴权）。
- **响应拦截器**（核心约定）：
    - 后端统一返回 `Result<T>`（`{code, message, data, timestamp}`）。
    - `code === 200`：**直接返回 `result.data`**，业务层拿到的就是纯数据。
    - `code !== 200`：统一 `ElMessage.error` 提示并 reject，页面无需重复处理错误。
    - 非 Result 结构（如文件流）原样返回。
    - HTTP 层错误（404 / 5xx / 超时）统一转换为中文提示。

### 5.2 通用方法（`api/http.ts`）

```typescript
get<T>(url, params?, config?)   // GET，params 序列化为 query string
post<T>(url, data?, config?)
put<T>(url, data?, config?)
del<T>(url, config?)
```

泛型 `T` 为拆解后的 `Result.data` 类型。

### 5.3 Vite 代理（`vite.config.ts`）

```
/api  →  http://localhost:8080/aifp   （rewrite 去掉 /api 前缀）
```

前端所有请求（含 SSE）统一走 `/api` 前缀，避免跨域且与后端 context-path 解耦。

---

## 6. 接口层（`src/api/`）

按后端 Controller 一一对应划分文件。

### 6.1 表单模块（`api/form.ts` → FormController）

| 方法                                | 后端接口                                    | 说明                         |
|-----------------------------------|-----------------------------------------|----------------------------|
| `createForm(data)`                | `POST /form/create`                     | 创建表单（表单 + 字段同事务），返回 formId |
| `getFormList({pageNum,pageSize})` | `GET /form/page`                        | 分页列表（列表项不含 fields）         |
| `getFormDetail(id)`               | `GET /form/{id}`                        | 详情（含字段列表）                  |
| `addField(formId, data)`          | `POST /form/{formId}/field`             | 追加字段                       |
| `deleteField(formId, fieldId)`    | `DELETE /form/{formId}/field/{fieldId}` | 删除单个字段                     |
| `deleteForm(id)`                  | `DELETE /form/{id}`                     | 删除表单（级联软删字段）               |

### 6.2 文件模块（`api/file.ts` → FileController）

| 方法                                | 后端接口                | 说明                                                                  |
|-----------------------------------|---------------------|---------------------------------------------------------------------|
| `uploadFile(file, onProgress?)`   | `POST /file/upload` | `multipart/form-data`；手动构造 FormData；`onUploadProgress` 回传 0-100 百分比 |
| `getFileList({pageNum,pageSize})` | `GET /file/page`    | 分页列表（AI 填报页文件下拉用）                                                   |

### 6.3 任务模块（`api/task.ts` → TaskController）

| 方法                            | 后端接口         | 说明                          |
|-------------------------------|--------------|-----------------------------|
| `startTask({formId, fileId})` | `POST /task` | 启动异步解析任务，返回 `{taskId, ...}` |

任务进度通过 **SSE** 获取（见 §8），不走 Axios。

---

## 7. 类型定义（`src/types/`）

与后端 DTO/VO 严格对齐，枚举值即后端枚举字符串：

- **`api.ts`**：`Result<T>`、`RESULT_SUCCESS_CODE=200`、`PageResult<T>`（`records/total/current/size`）。
- **`form.ts`**：`FieldType`（STRING/INTEGER/DECIMAL/DATE/BOOLEAN）+
  中文映射 `FIELD_TYPE_LABELS`；`FormCreateDTO`、`FormFieldCreateDTO`
  （fieldName/fieldCode/fieldType/required/description/sort）；`FormVO`、`FormFieldVO`。
- **`file.ts`**：`FileType`（PDF/EXCEL/WORD/TXT/OTHER）、`FileStatus`（UPLOADED→PARSING→VECTORING→EXTRACTING→SUCCESS/FAILED）+
  中文映射；`ACCEPT_EXT = '.pdf,.xlsx,.xls,.docx,.doc,.txt'`；`FileUploadVO`、`FileRecordVO`。
- **`task.ts`**：`TaskStatus`（PARSING/VECTORING/EXTRACTING/SUCCESS/FAILED）+ 中文映射 + 阶段百分比常量 `TASK_PERCENT`
  （0/50/80/100/-1）；`TaskStartRequest`、`TaskStartVO`、`TaskProgress`、`ExtractionResult`
  （values/errors/attemptsUsed）、`FieldError`。

### 重要约定：大整数 ID 精度处理

后端使用雪花算法生成 ID，超出 JS `Number.MAX_SAFE_INTEGER`。因此：

- **所有 ID 在前端一律按字符串传递与保存**（`number | string` 类型兼容，取值后 `String()` 转换）。
- ID 输入框限制只能输入数字但保持字符串类型（见 `FormList.vue` 的 `handleIdInput`）。
- `lastUpload.ts` 持久化时强制 `String(vo.fileId)`。

---

## 8. SSE 实时进度（`utils/sse.ts`）

基于浏览器原生 `EventSource` 封装：

```typescript
const es = subscribeTaskProgress(taskId, {
  onOpen?: () => void,
  onProgress: (p: TaskProgress) => void,
  onError?: (err: Event) => void
})
```

关键行为：

- 订阅地址：`/api/task/progress/{taskId}`（走 Vite 代理）。
- 监听后端事件名 **`progress`**（对应后端 `SseEmitter.event().name("progress")`）。
- 每帧 JSON 解析为 `TaskProgress` 后回调 `onProgress`。
- **终态自动关闭**：`status ∈ {SUCCESS, FAILED}` 或 `percent ∈ {100, -1}` 时 `es.close()`。
- **连接异常主动关闭**，避免 EventSource 默认无限重连。
- 使用方负责在组件卸载 / 重置 / 重新启动时调用 `es.close()`（`AiFill.vue` 在 `onUnmounted` 中清理）。

---

## 9. 状态管理（`stores/task.ts`）

Pinia setup 风格 store，仅维护状态，SSE 逻辑在 `utils/sse.ts`：

| 状态                  | 类型                       | 说明               |
|---------------------|--------------------------|------------------|
| `currentTaskId`     | string                   | 当前任务 ID          |
| `fileId` / `formId` | number \| string         | 关联 ID（字符串化防精度丢失） |
| `status`            | TaskStatus \| ''         | 任务阶段             |
| `percent`           | number                   | 进度百分比            |
| `message`           | string                   | 阶段消息             |
| `result`            | ExtractionResult \| null | AI 抽取结果          |
| `loading`           | boolean                  | 任务是否进行中          |

方法：`setTask(taskId, fileId, formId)` 初始化；`updateProgress(p)` 接收 SSE 帧；`finish()` 标记结束；`reset()` 清空全部。

---

## 10. 本地持久化（`utils/lastUpload.ts`）

- 存储 key：`aifp_last_upload`，内容为 `{fileId, fileName, fileType, createTime}`。
- `setLastUpload(vo)`：上传成功后保存（fileId 转字符串）；`getLastUpload()`：读取（解析失败返回 null）；`clearLastUpload()`：清除。
- 用途：`FileUpload.vue` 上传成功后写入 → `AiFill.vue` 挂载时读取，**预填最近上传的文件**，串联两个页面。

---

## 11. 布局组件（`layouts/MainLayout.vue`）

- 企业后台经典布局：顶部深色 Header（Logo + 标题 + 版本号）+ 左侧可折叠 Sidebar（220px ↔ 64px）+ 主内容区（带 fade
  过渡的 `<RouterView>`）。
- 菜单项硬编码于 `menuItems`；`activeMenu` 计算属性做前缀匹配（如 `/form/123` 高亮"表单管理"）。
- 全局 CSS
  变量定义在 `styles/index.scss`：`--app-header-height: 56px`、`--app-sidebar-width: 220px`、`--app-sidebar-collapsed-width: 64px`。

---

## 12. 页面模块详解

### 12.1 表单管理（`views/form/`）

**FormList.vue** — 列表页

- `GET /form/page` 分页加载（按创建时间倒序）；顶部支持按表单 ID 直查（先调详情接口校验存在性再跳详情页）。
- 删除走 `ElMessageBox.confirm` 二次确认；删除后若当前页只剩 1 条且非首页自动回退一页防空页。

**FormCreate.vue** — 新建页

- 表单头（formName 必填 ≤100 字符、description ≤500 字符）+ 动态字段表格。
- 字段通过 `FormFieldEditor` Dialog 新增/编辑（本地暂存 `fieldList`，可编辑、可删除）。
- 一次性提交 `POST /form/create`（后端表单 + 字段同事务），成功后 `router.replace('/form/{id}')`。

**FormDetail.vue** — 详情页

- 展示表单头 + 字段列表；支持追加字段（`POST /form/{id}/field`，编辑器复用）与删除字段（`DELETE /form/{id}/field/{fieldId}`）。
- 挂载时校验路由参数 `id` 为纯数字，非法则提示并跳回列表。

### 12.2 文件上传（`views/file/FileUpload.vue`）

- `el-upload` 拖拽模式 + `:auto-upload="false"`：**不依赖组件默认上传**，选中文件后手动调用 `uploadFile()`（FormData）。
- 前端预校验：类型受 `ACCEPT_EXT` 限制；大小上限 100MB（与后端 `max-file-size` 一致），超限直接拒收。
- 进度条来自 axios `onUploadProgress` 回调；上传成功展示结果卡（fileId / 类型 / 状态 / 存储路径），并提供"前往 AI 填报"跳转。

### 12.3 AI 自动填报（`views/fill/AiFill.vue`）

核心流程（三区结构：配置区 / 进度区 / 结果区）：

1. **配置区**：表单下拉 + 文件下拉（各取第一页 50 条）；选择表单后拉取详情渲染字段预览 Tag；最近上传文件自动预填。
2. **启动任务**：`handleStart()` 校验 → 关闭旧
   SSE → `taskStore.reset()` → `startTask({formId, fileId})` → `taskStore.setTask(...)` → `subscribeTaskProgress(taskId, handlers)`。
3. **进度区**：进度条 + 状态 Tag + 阶段消息 + `el-steps` 时间线（解析 → 向量化 → AI 抽取 → 完成）。
4. **结果区**：`ExtractionResult.values` 按表单字段映射为表格（未抽到显示占位）；`errors`
   数组单独渲染错误表（字段编码/错误类型/消息/原始值）；展示重试次数 `attemptsUsed`。
5. **生命周期**：终态成功弹 `ElMessage.success`，失败写入 `errorMsg` 并弹错误；SSE 连接异常仅在非终态时提示；`onUnmounted`
   关闭连接防泄漏；支持"重置"重新选择启动。

### 12.4 可复用组件（`components/FormFieldEditor.vue`）

- 字段编辑 Dialog，`v-model:visible` 控制显隐，`confirm` 事件回传 `FormFieldCreateDTO`。
- Props：`visible`、`existingCodes`（唯一性校验）、`initialData`（编辑模式初值，null 为新增）、`maxSort`（新增时 sort 自动 +1）。
- 校验规则：fieldName 必填 ≤100；fieldCode 必填 ≤64、正则 `^[a-zA-Z][a-zA-Z0-9_]*$`、表单内唯一（编辑模式且编码未变时跳过唯一性校验）；description
  ≤500。
- 被 `FormCreate.vue` 与 `FormDetail.vue` 复用。

---

## 13. 开发与构建

```bash
npm install          # 安装依赖
npm run dev          # 启动开发服务器（http://localhost:5173，自动打开浏览器）
npm run type-check   # vue-tsc 类型检查
npm run build        # 类型检查 + 生产构建
npm run preview      # 预览生产构建产物
```

前置条件：后端服务运行在 `http://localhost:8080/aifp`（开发代理目标），否则 `/api` 请求与 SSE 均不可用。

---

## 14. 后端接口对照总表

| 前端调用                                        | 后端接口                                               | 模块 |
|---------------------------------------------|----------------------------------------------------|----|
| `POST /api/form/create`                     | `POST /aifp/form/create`                           | 表单 |
| `GET /api/form/page`                        | `GET /aifp/form/page`                              | 表单 |
| `GET /api/form/{id}`                        | `GET /aifp/form/{id}`                              | 表单 |
| `POST /api/form/{formId}/field`             | `POST /aifp/form/{formId}/field`                   | 表单 |
| `DELETE /api/form/{formId}/field/{fieldId}` | `DELETE /aifp/form/{formId}/field/{fieldId}`       | 表单 |
| `DELETE /api/form/{id}`                     | `DELETE /aifp/form/{id}`                           | 表单 |
| `POST /api/file/upload`                     | `POST /aifp/file/upload`                           | 文件 |
| `GET /api/file/page`                        | `GET /aifp/file/page`                              | 文件 |
| `POST /api/task`                            | `POST /aifp/task`                                  | 任务 |
| `SSE /api/task/progress/{taskId}`           | `GET /aifp/task/progress/{taskId}`（事件名 `progress`） | 任务 |

---

## 15. 开发约定速查

1. **ID 一律字符串化**：所有雪花 ID 按字符串传递/存储，防大整数精度丢失。
2. **错误统一处理**：业务错误由 Axios 响应拦截器 `ElMessage` 提示并 reject，页面 `catch` 中只处理取消/静默场景，不重复弹错。
3. **响应已拆包**：API 层返回值即 `Result.data`，不要再解 `.data`。
4. **SSE 必须清理**：组件卸载、重置、重启前都要 `close()`，连接由 `utils/sse.ts` 统一管理终态与异常关闭。
5. **类型与后端对齐**：新增接口先在 `types/` 补 DTO/VO 与枚举映射，再在 `api/` 补方法，页面只依赖类型与 API 层。
6. **新增页面**：`views/` 建组件 → `router/index.ts` 注册路由 → `MainLayout.vue` 的 `menuItems` 登记菜单。
7. **新增枚举展示**：使用 `types/` 中现成的 `*_LABELS` 中文映射，保持 UI 文案统一。
8. **项目（Project）功能开发**：见 [project-frontend-dev-guide.md](./project-frontend-dev-guide.md)（增量开发指南：项目
   CRUD / 文件与表单关联 / 项目助手对接 / Phase 0 契约修复前置）。
