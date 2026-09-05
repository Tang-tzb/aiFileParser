# 文件解析 → AI 填报链路技术文档

> **本文档定位**：供 AI 智能体/新成员快速理解「文件上传 → 解析 → 向量化 → AI 字段抽取 → 填报结果」的完整实现链路。
> 与 [PROJECT_CONTEXT.md](PROJECT_CONTEXT.md)（全局项目上下文）配套阅读；本文聚焦解析→填报主链路的**实现细节**。
> **基于源码整理，对应最新提交** `0afa573 feat:ai解析文件填报表单`（2026-09-05 核对）。

---

## 1. 代码结构总览

```
src/main/java/com/aifp/aiagent/
├── AiFileParserApplication.java      # 启动类：@MapperScan("...repository") @EnableTransactionManagement
├── common/                           # Result<T> 统一返回 + ResultCode 错误码枚举
├── config/                           # AsyncConfig(parseExecutor线程池) / RedisConfig / MyBatis-Plus 配置
├── controller/                       # REST 接口层（FormController/FileController/FillController/TaskController/HealthController）
├── document/                         # 解析产物统一模型：ParserDocument + ParserDocumentMetadata
├── dto/                              # ExtractionResult/TaskProgress/各VO/分页PageQuery/PageResult
├── entity/                           # 数据库实体（BaseEntity + FileRecord/FormDefinition/FormFieldDefinition）+ enums
├── exception/                        # BusinessException + GlobalExceptionHandler
├── parser/                           # ★ 文件解析层（策略模式）
│   ├── FileParser.java               #   解析器接口：supportedType() + parse(File)
│   ├── FileParserRegistry.java       #   解析器注册表：FileType → FileParser 路由
│   ├── PdfParser.java                #   PDF：PDFBox PDFTextStripper
│   ├── ExcelParser.java              #   Excel：POI WorkbookFactory
│   ├── WordParser.java               #   Word：POI XWPFDocument（仅 .docx）
│   └── ocr/                          #   OCR 预留：OcrParser 接口 + PaddleOcrParser 桩实现
├── rag/                              # ★ RAG 知识库 + AI 抽取组件
│   ├── DocumentChunker.java          #   JTokkit token 滑动窗口分块
│   ├── DocumentIngestionService(+Impl) # 入库编排：解析→分块→向量化（幂等）
│   ├── EmbeddingService(+Impl)       #   DashScope text-embedding-v2 向量
│   ├── VectorStoreService(+Impl)     #   Milvus 存取（支持 fileId 过滤检索）
│   ├── FieldQueryGenerator.java      #   按字段生成检索 query
│   ├── ExtractionPromptBuilder.java  #   动态 JSON Schema Prompt 构造 + Retry 反馈
│   ├── FieldSchemaValidator.java     #   抽取结果 Schema 校验 + 智能类型转换
│   └── ProgressCallback.java         #   入库阶段回调（PARSING/VECTORING）
├── repository/                       # 数据访问层：仅 BaseMapper 继承，无自定义 SQL
├── service/                          # 业务服务（接口 + impl）
│   ├── FieldExtractorService(+Impl)  # ★ AI 抽取引擎主流程
│   ├── FileService(+Impl)            #   上传/状态流转/查询/分页
│   ├── FormService(+Impl)            #   动态表单 CRUD/分页
│   ├── ParseTaskService(+Impl)       #   异步任务发起
│   └── storage/                      #   FileStorageService + LocalFileStorageService
└── task/                             # ★ 异步任务与进度推送
    ├── AsyncParseExecutor.java       #   @Async 流水线编排（0→50→80→100/-1）
    ├── ProgressPublisher.java        #   进度双写 Redis（快照 + Pub/Sub）
    ├── ProgressMessageListener.java  #   Redis 订阅 → 路由 SSE
    └── SseEmitterManager.java        #   SSE 连接管理（注册/发送/完成/移除）

web/                                  # Vue3 前端（独立工程，调用 /aifp 接口 + SSE）
```

---

## 2. 端到端链路总览

系统提供**两个入口**进入同一条「解析→填报」流水线：

| 入口   | 接口                                 | 模式                               | 适用场景     |
|------|------------------------------------|----------------------------------|----------|
| 同步填报 | `POST /aifp/fill/{formId}?fileId=` | 阻塞直至抽取完成，直接返回 `ExtractionResult` | 后端调用/小文件 |
| 异步任务 | `POST /aifp/task` + SSE 订阅         | 立即返回 taskId，进度经 Redis→SSE 实时推送   | 前端页面（推荐） |

### 2.1 完整时序（异步主链路）

```
前端 web/                          后端
   │
   ├─ POST /file/upload ──────→ FileController → FileServiceImpl.upload
   │      返回 fileId(字符串)      └─ LocalFileStorageService.store + file_record(UPLOADED)
   │
   ├─ POST /task {formId,fileId} → TaskController → ParseTaskServiceImpl.start
   │      返回 taskId              └─ 校验表单/文件 → UUID taskId → 发布初始 0% → asyncParseExecutor.run
   │
   ├─ GET /task/progress/{taskId}  (SSE, 事件名 "progress")
   │      首帧=Redis快照(断线重连)  → SseEmitterManager.register + sendSnapshotIfPresent
   │
   │   ┌─────────── parseExecutor 线程池（AsyncParseExecutor.run）───────────┐
   │   │ 0%  PARSING   ← DocumentIngestionServiceImpl.ingest(fileId, callback)│
   │   │       └─ FileParserRegistry.get(type).parse(file) → ParserDocument  │
   │   │ 50% VECTORING ← DocumentChunker.chunk → VectorStoreService.store     │
   │   │ 80% EXTRACTING ← FieldExtractorServiceImpl.extract(formId, fileId)   │
   │   │       └─ 检索→Prompt→ChatModel(Qwen-Plus)→校验→Retry（见 §3.6）      │
   │   │ 100% SUCCESS  携带 ExtractionResult  /  -1 FAILED                    │
   │   └──────────────────────────────────────────────────────────────────────┘
   │
   │        每次 publish：Redis 双写（快照 task:progress:{taskId} TTL 3h + Pub/Sub channel task:progress）
   │              ↓
   │        ProgressMessageListener.onMessage → SseEmitterManager.send(taskId, progress)
   │              ↓ 终态自动 complete emitter
   └─ 前端收到 100% 帧，ExtractionResult.values 填充表单
```

同步入口 `/fill` 则由 `FillController` 直接调用 `FieldExtractorServiceImpl.extract`（内部同样先幂等 ingest），无进度推送。

---

## 3. 分阶段实现详解

### 3.1 任务发起与编排（service/task 包）

**[ParseTaskServiceImpl](../../java/com/aifp/aiagent/service/impl/ParseTaskServiceImpl.java)**

- `start(Long formId, Long fileId)`：
    1. `formService.getFormById` + `fileService.getById` 校验存在性（不存在抛 5001/2004）；
    2. 生成 `taskId = UUID.randomUUID().toString()`；
    3. `publishInitial`：构造初始 `TaskProgress(0%, PARSING, "任务初始化")` 并发布（前端秒开即有进度）；
    4. 调用 `asyncParseExecutor.run(taskId, formId, fileId)` 触发异步执行。

**[AsyncParseExecutor](../../java/com/aifp/aiagent/task/AsyncParseExecutor.java)**

- `@Async("parseExecutor") void run(...)`：**独立 bean + 跨 bean 调用**，确保 `@Async` 代理生效（同类内部调用会失效）。
- 编排逻辑（每步用 `base.with(...)` 拷贝工厂生成下一阶段进度）：

```
ingestWithProgress  →  回调驱动 0%(PARSING)/50%(VECTORING)
publish 80% EXTRACTING
fieldExtractorService.extract(formId, fileId)   ← 内部幂等 ingest，因已 SUCCESS 直接跳过
publish 100% SUCCESS（result 携带 ExtractionResult）
catch → publish -1 FAILED（message 含异常信息）
```

- 线程池 `parseExecutor`（[AsyncConfig](../../java/com/aifp/aiagent/config/AsyncConfig.java)）：core 2 / max 4 / queue
  100 / `CallerRunsPolicy`。

### 3.2 文件解析层（parser 包，策略模式）

**接口与路由**

- [FileParser](../../java/com/aifp/aiagent/parser/FileParser.java)：`FileType supportedType()` + `ParserDocument parse(File file)`。
- [FileParserRegistry](../../java/com/aifp/aiagent/parser/FileParserRegistry.java)：Spring 注入 `List<FileParser>`
  构造 `Map<FileType, FileParser>`；`get(type)` 无匹配解析器抛 `FILE_TYPE_NOT_SUPPORT(2002)`。*
  *新增解析器只需实现接口 + `@Component`，注册表自动收集。**

**各解析器实现**

| 解析器                                                                | 类型    | 技术                                          | 内容抽取规则                                             | metadata.page 语义 |
|--------------------------------------------------------------------|-------|---------------------------------------------|----------------------------------------------------|------------------|
| [PdfParser](../../java/com/aifp/aiagent/parser/PdfParser.java)     | PDF   | PDFBox `Loader.loadPDF` + `PDFTextStripper` | 全文文本抽取；**扫描版（图片型）PDF 抽取结果为空**                      | 文档页数             |
| [ExcelParser](../../java/com/aifp/aiagent/parser/ExcelParser.java) | EXCEL | POI `WorkbookFactory`（自动识别 xls/xlsx）        | 工作表→行→单元格，分隔符 `\n\n` / `\n` / `\t`                 | 工作表数             |
| [WordParser](../../java/com/aifp/aiagent/parser/WordParser.java)   | WORD  | POI `XWPFDocument`                          | 遍历段落；**仅支持 .docx**，.doc 抛 `FILE_PARSE_ERROR(2001)` | 段落数              |

**解析产物统一模型 [ParserDocument](../../java/com/aifp/aiagent/document/ParserDocument.java)**

- `String content` —— 全文文本；
- `ParserDocumentMetadata metadata` —— `fileName` / `page` / `FileType type` / `Long fileId`（fileId 由入库编排注入，供
  chunk 元数据按文件过滤）。

**OCR 预留（[parser/ocr](../../java/com/aifp/aiagent/parser/ocr/)）**

- `OcrParser` 接口：`String recognize(File file)`；
- `PaddleOcrParser`：**桩实现**，调用即抛 `UnsupportedOperationException`。尚未接入真实 PaddleOCR 服务，扫描版 PDF 当前无法提取内容。

### 3.3 入库编排与幂等（rag 包）

**[DocumentIngestionServiceImpl](../../java/com/aifp/aiagent/rag/impl/DocumentIngestionServiceImpl.java)** ——
解析→分块→向量化的编排者：

```
ingest(fileId)            → 委托 ingest(fileId, null)
ingest(fileId, callback)  → getById 幂等校验（状态已 SUCCESS 直接返回，不重复入库）
    → notifyStage("PARSING")        [回调驱动异步进度 0%]
    → parseDocument：storage.load(filePath) → registry.get(fileType).parse(file)
    → metadata.setFileId(fileId)
    → notifyStage("VECTORING")      [回调驱动异步进度 50%]
    → chunker.chunk(doc) → vectorStore.store(chunks)
    → 文件状态 → SUCCESS
异常 → markFailed（状态 FAILED），并吞掉标记过程中的二次异常以保留原始异常
```

- **幂等设计**：`extract` 内部会再次调用 `ingest`，依靠 `FileStatus == SUCCESS` 判断跳过，保证异步链路（executor 已
  ingest）与同步链路（extract 内 ingest）不重复解析/向量化。
- **状态机**（[FileStatus](../../java/com/aifp/aiagent/entity/enums/FileStatus.java)）：
  `UPLOADED → PARSING → VECTORING → EXTRACTING → SUCCESS / FAILED`（由 FileService.updateStatus 流转，与
  TaskProgress.status 同名同步）。

### 3.4 语义分块（DocumentChunker）

- 分词器：JTokkit `EncodingType.CL100K_BASE`（与 LLM token 口径一致）；
- 配置：`rag.chunk.size=800`、`rag.chunk.overlap=200`（token 滑动窗口）；
- 输出 Spring AI `Document` 列表，每片元数据：`fileName / fileType / page / fileId / chunkIndex / totalChunks`；
- **overlap 保证跨块边界的上下文连续性**，chunkIndex/totalChunks 支持按序还原。

### 3.5 向量化与检索（rag.impl）

| 组件                                                                                         | 依赖                                                                           | 职责                                                                            |
|--------------------------------------------------------------------------------------------|------------------------------------------------------------------------------|-------------------------------------------------------------------------------|
| [EmbeddingServiceImpl](../../java/com/aifp/aiagent/rag/impl/EmbeddingServiceImpl.java)     | Spring AI `EmbeddingModel`（DashScope text-embedding-v2，1536 维）               | `embed(text)` / `embedBatch(texts)`                                           |
| [VectorStoreServiceImpl](../../java/com/aifp/aiagent/rag/impl/VectorStoreServiceImpl.java) | Spring AI `VectorStore`（Milvus：collection `aifp_doc_chunks`，IVF_FLAT/COSINE） | `store(chunks)`=vectorStore.add；`search(query, topK, filterExpression)` 带过滤检索 |

- **关键设计——fileId 过滤**：检索时拼接 `fileId == '{fileId}'` 表达式，**避免跨文件内容污染抽取结果**
  。维度须与 `spring.ai.vectorstore.milvus.embedding-dimension=1536` 一致。

### 3.6 AI 抽取引擎（★ 核心链路）

**[FieldExtractorServiceImpl](../../java/com/aifp/aiagent/service/impl/FieldExtractorServiceImpl.java)**
主流程 `extract(Long formId, Long fileId)`：

```
① ingestionService.ingest(fileId)          // 幂等入库（已 SUCCESS 跳过）
② loadFields(formId)                       // 表单不存在抛 FORM_NOT_FOUND；字段空抛 FORM_FIELD_EMPTY
③ fileService.updateStatus(EXTRACTING)
④ retrieveAndMerge(fields, filter)         // 逐字段检索 + Document.id 去重保序（LinkedHashMap）
     对每个字段：query = fieldQueryGenerator.generate(field)
                hits = vectorStoreService.search(query, topK, "fileId == '...'")
     chunks 为空 → 抛 VECTOR_RETRIEVE_ERROR
⑤ extractWithRetry(fields, chunks)         // Retry 循环（见下）
⑥ fileService.updateStatus(SUCCESS) → 返回 ExtractionResult
```

**Retry 循环 `extractWithRetry`**（配置 `rag.extract.retry.max-attempts=2`，含首次共 3 次 LLM 调用）：

```
userPrompt = buildUserPrompt(chunks)          // 固定，只构造一次
for i in 0..maxAttempts:
    systemPrompt = buildSystemPrompt(fields, feedback)   // 首轮 feedback=null
    raw = parseJson(callChatModel(systemPrompt, userPrompt))
    vr  = fieldSchemaValidator.validate(raw, fields)
    无错误 → 返回成功结果
    有错误 → feedback = buildRetryFeedback(vr.errors) 注入下一轮
重试耗尽 → 返回 values + 剩余 errors（部分成功也返回，前端据此标红错误字段）
```

- **JSON 解析失败不参与字段级 Retry**：`parseJson` 直接抛 `AI_RESPONSE_PARSE_ERROR(3002)`（属响应格式问题而非字段问题）；
- `callChatModel`：`SystemMessage + UserMessage` → `chatModel.call(prompt)`，异常转 `AI_INVOKE_ERROR(3001)`；
- `parseJson` 内置 `stripCodeFence`：剥离 LLM 可能输出的 ` ```json ... ``` ` markdown 围栏后再反序列化。

**Prompt 构造**

- [FieldQueryGenerator](../../java/com/aifp/aiagent/rag/FieldQueryGenerator.java)：检索 query 模板 `"请从文档中提取%s。"`
  （fieldName 为主，description 非空则追加）；
- [ExtractionPromptBuilder](../../java/com/aifp/aiagent/rag/ExtractionPromptBuilder.java)：
    - System Prompt 内嵌**动态 JSON Schema**，格式：`{"fieldCode":"type,必填?,fieldName,description?"}`；
    - 类型映射复用 `FieldType.getJsonSchemaType()`（无硬编码，新增字段类型自动生效）；
    - `buildRetryFeedback(errors)` 把失败字段清单作为反馈注入，引导 AI 定向修正。

**结果校验与类型转换（[FieldSchemaValidator](../../java/com/aifp/aiagent/rag/FieldSchemaValidator.java)）**

- 编程式实现动态字段的 Bean Validation 语义（字段运行时动态，无法用注解式校验）；
- 错误三类：`MISSING`（必填缺失）/ `TYPE`（类型不符）/ `FORMAT`（格式无法解析），错误字段 coerced 置 null；
- **智能类型转换**：

| 目标类型    | 转换规则                                                                                           |
|---------|------------------------------------------------------------------------------------------------|
| INTEGER | 支持「万/亿」单位、千分位逗号；`longValueExact` 拒绝小数                                                          |
| DECIMAL | BigDecimal，`stripTrailingZeros`                                                                |
| BOOLEAN | true/false/1/0                                                                                 |
| DATE    | 4 种格式按优先级尝试：`yyyy-MM-dd` / `yyyy/MM/dd` / `yyyy年MM月dd日` / `yyyyMMdd` → **java.time.LocalDate** |
| STRING  | 原样                                                                                             |

- 返回 `ValidationResult{coerced, errors, hasErrors()}`。

### 3.7 进度推送链路（task 包）

```
ProgressPublisher.publish(TaskProgress)
   ├─ 快照：opsForValue().set("task:progress:{taskId}", progress, TTL 3h)   ← 断线重连补发
   └─ Pub/Sub：convertAndSend("task:progress", progress)                    ← 实时扇出
        ↓ RedisMessageListenerContainer（RedisConfig 注册）
ProgressMessageListener.onMessage
   └─ valueSerializer 对称反序列化为 TaskProgress（复用 RedisConfig 的 redisValueSerializer bean）
        → sseEmitterManager.send(taskId, progress)
        → isTerminal() → complete(taskId)（防连接泄漏）
SseEmitterManager：ConcurrentMap<taskId, SseEmitter>，register 时绑定 onCompletion/onTimeout/onError 自动移除
```

- **SSE 契约**：事件名 `progress`，data 为 `TaskProgress` JSON；`TaskController.progress`
  建立连接后先 `sendSnapshotIfPresent` 补发快照首帧（已终态则立即 complete）；
- **序列化对称性**：发布端与订阅端必须复用同一 `RedisSerializer<Object>`（`GenericJackson2JsonRedisSerializer`
  ，保留 `@class` 类型信息）；该 bean 声明为 `static` 以打破 RedisConfig ⇄ ProgressMessageListener 循环依赖；
- 进度发布失败仅 warn 日志，**不影响主流程**（Redis 不可用时同步 `/fill` 仍可用）。

---

## 4. 关键数据结构契约

### ExtractionResult（填报结果，100% 帧 result 字段）

```json
{
  "values":        { "fieldCode": "类型化值（INTEGER→Long, DECIMAL→BigDecimal, DATE→LocalDate…）" },
  "errors":        [ { "fieldCode": "...", "errorType": "MISSING|TYPE|FORMAT", "message": "...", "rawValue": ... } ],
  "attemptsUsed":  2
}
```

### TaskProgress（SSE 事件载荷 + Redis 快照）

- `taskId / fileId / formId / status / percent / message / result / timestamp`；
- 进度码：`0=PARSING, 50=VECTORING, 80=EXTRACTING, 100=SUCCESS, -1=FAILED`；
- `isTerminal()`（percent==100 或 FAILED）标注 `@JsonIgnore`（派生属性，若被序列化会导致订阅端反序列化失败）；
- 类标 `@JsonIgnoreProperties(ignoreUnknown = true)`（前后兼容）；
- `with(status, percent, message, result)` 拷贝工厂，复用 taskId/fileId/formId。

### Long ID 序列化（强约定）

- 雪花算法 ID 超出 JS `Number.MAX_SAFE_INTEGER`，**所有 VO/DTO 中的 Long 型
  ID（formId/fileId/fieldId）必须标注 `@JsonSerialize(using = ToStringSerializer.class)`**
  ，以字符串形式传输（TaskProgress、FormVO、FileRecordVO、FormFieldVO、FileUploadVO 等均已处理）。

---

## 5. 配置项速查（application.yml）

| 配置                                             | 默认值                            | 说明                             |
|------------------------------------------------|--------------------------------|--------------------------------|
| `spring.ai.dashscope.api-key`                  | `${AI_DASHSCOPE_API_KEY:}`     | **启动必需**，缺失则 DashScope 自动装配失败  |
| `spring.ai.dashscope.chat.options.model`       | qwen-plus / temperature 0.3    | 抽取模型（低温度保稳定）                   |
| `spring.ai.dashscope.embedding.options.model`  | text-embedding-v2              | 1536 维，须与 Milvus dimension 一致  |
| `spring.ai.vectorstore.milvus.collection-name` | aifp_doc_chunks                | initialize-schema=true 首启自动建集合 |
| `rag.chunk.size / overlap`                     | 800 / 200                      | 分块 token 窗口                    |
| `rag.retrieve.topK`                            | 5                              | 每字段检索条数                        |
| `rag.extract.retry.max-attempts`               | 2                              | 校验失败重试次数（共 LLM 调用 = 2+1）       |
| `task.sse.timeout-ms`                          | 1800000                        | SSE 连接超时 30 分钟                 |
| `task.progress.snapshot-ttl-hours`             | 3                              | Redis 快照 TTL                   |
| `file.upload-dir`                              | `${FILE_UPLOAD_DIR:./uploads}` | 本地存储根目录（yyyy/MM/uuid.ext）      |

---

## 6. 已知注意事项与陷阱（智能体必读）

1. **DATE 字段与 Redis 序列化的潜在冲突**：`FieldSchemaValidator` 将 DATE 转为 `java.time.LocalDate`
   ，而当前 `RedisConfig.redisValueSerializer()` 为原生 `GenericJackson2JsonRedisSerializer`（**未注册 JavaTimeModule**
   ）。异步链路 100% SUCCESS 帧若携带含 LocalDate 的 `ExtractionResult`，Pub/Sub 序列化会失败（日志出现进度发布 warn，SSE
   收不到最终帧；同步 `/fill` 不受影响）。修复方向：定制 ObjectMapper
   注册 `JavaTimeModule` + `disable(WRITE_DATES_AS_TIMESTAMPS)` + `BasicPolymorphicTypeValidator`。
2. **OCR 为桩实现**：扫描版（图片型）PDF 经 `PdfParser` 抽取文本为空 → 向量库无该文件内容 → 检索为空 →
   抛 `VECTOR_RETRIEVE_ERROR`。接入 PaddleOCR 时实现 `OcrParser` 接口并在 `PdfParser`/编排层挂接。
3. **@Async 代理**：必须跨 bean 调用（`ParseTaskServiceImpl → AsyncParseExecutor`），同类内部调用代理不生效。
4. **幂等依赖文件状态**：`DocumentIngestionServiceImpl` 以 `FileStatus.SUCCESS` 判重。手工把 file_record 状态改回
   UPLOADED/PARSING 可强制重新入库（调试用）。
5. **新增/修改字段类型的三处联动**：① `FieldType` 枚举（code/label/jsonSchemaType）；② `FieldSchemaValidator.coerce` 增加
   switch 分支；③ Prompt 侧无需改（自动读 jsonSchemaType）。
6. **新增文件解析器**：实现 `FileParser` + `@Component`，`FileParserRegistry` 自动收集；`FileType` 枚举需含扩展名集合。
7. **测试必须离线可跑**：standalone MockMvc + Mockito，不依赖 Spring 上下文/MySQL/Milvus/Redis；ObjectMapper 需注册
   JavaTimeModule（VO 含 LocalDateTime）。
8. **方法 ≤ 50 行、Controller 不写业务**：抽取主流程的超长逻辑拆私有方法（参考 `FieldExtractorServiceImpl`）。

---

## 7. 接口清单（解析→填报相关）

| 方法     | 路径                                | 说明                                      |
|--------|-----------------------------------|-----------------------------------------|
| POST   | `/aifp/file/upload`               | 上传文件，返回 `FileUploadVO`（fileId 为字符串）     |
| GET    | `/aifp/file/page`                 | 分页查文件列表（createTime 倒序）                  |
| POST   | `/aifp/form/create`               | 创建表单（表单头+字段批插）                          |
| GET    | `/aifp/form/{id}`                 | 表单详情（字段按 sort 升序）                       |
| GET    | `/aifp/form/page`                 | 分页查表单列表                                 |
| POST   | `/aifp/form/{id}/field`           | 追加字段                                    |
| DELETE | `/aifp/form/{id}/field/{fieldId}` | 删除字段（软删）                                |
| DELETE | `/aifp/form/{id}`                 | 删除表单（级联软删字段）                            |
| POST   | `/aifp/fill/{formId}?fileId=`     | **同步**抽取填报，返回 `ExtractionResult`        |
| POST   | `/aifp/task`                      | **异步**发起任务，返回 `TaskStartVO{taskId,...}` |
| GET    | `/aifp/task/progress/{taskId}`    | SSE 订阅进度（事件名 `progress`，终态自动关闭）         |

统一返回 `Result<T>{code, message, data, timestamp}`；错误码分段：2xxx 文件 / 3xxx AI / 4xxx 向量 / 5xxx 表单。
