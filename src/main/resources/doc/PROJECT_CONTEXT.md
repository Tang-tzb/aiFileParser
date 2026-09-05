# aiFileParser 项目上下文文档

> 本文档供新对话 AI 快速理解项目现状与代码结构。基于源码逐类整理，反映截至阶段 8 完成时的代码状态。
> **最后更新**：阶段 8 完成（异步任务 + SSE 进度推送 + Redis 集成）
> **测试状态**：`mvn test` 68 个用例全部绿，BUILD SUCCESS

---

## 1. 项目概述

### 项目名称

aiFileParser —— 企业级 AI 文件自动解析自动填报系统

### 项目目标

实现：用户上传业务文件(PDF/Excel/Word) → 系统自动解析文件内容 → 根据用户自定义表单字段 → 调用 AI 模型提取对应字段 →
自动生成结构化数据 → 填充业务表单。

### 核心能力

1. 动态表单设计（运行时增字段无需改代码）
2. 文件解析（PDF/Excel/Word/OCR 预留）
3. RAG 知识库（向量化 + 相似检索）
4. AI 字段抽取（Spring AI + Qwen-Plus）
5. 抽取结果可靠性保障（JSON Schema 校验 + Retry 反馈 + 智能类型转换）
6. 异步任务与 SSE 实时进度推送（阶段 8）

---

## 2. 技术栈

| 类别       | 选型                                     | 版本                                |
|----------|----------------------------------------|-----------------------------------|
| 后端语言     | Java                                   | 21                                |
| 框架       | Spring Boot                            | 3.5.13                            |
| AI 框架    | Spring AI + Spring AI Alibaba          | Spring AI 1.0.0 / Alibaba 1.0.0.2 |
| 构建       | Maven                                  | -                                 |
| 数据库      | MySQL                                  | 8.0.46                            |
| ORM      | MyBatis-Plus                           | 3.5.16（+ mybatis-plus-jsqlparser） |
| AI 模型    | Qwen-Plus（阿里云百炼 DashScope）             | temperature 0.3                   |
| 文本向量模型   | DashScope text-embedding-v2            | 1536 维                            |
| 向量数据库    | Milvus                                 | IVF_FLAT / COSINE                 |
| 文件解析     | Apache PDFBox / Apache POI             | 3.0.4 / 5.3.0                     |
| Token 分词 | JTokkit (cl100k_base)                  | 随 spring-ai-bom                   |
| 缓存/消息    | Redis                                  | Lettuce 池                         |
| 异步       | Spring @Async + ThreadPoolTaskExecutor | parseExecutor 线程池                 |
| 实时推送     | Spring WebMvc SseEmitter               | -                                 |
| OCR      | PaddleOCR                              | 预留接口，未实现                          |
| 工具       | Lombok                                 | -                                 |

---

## 3. 开发阶段规划

| 阶段   | 内容                                | 状态   |
|------|-----------------------------------|------|
| 阶段 1 | 项目骨架与基础配置                         | ✅ 完成 |
| 阶段 2 | 动态表单管理模块                          | ✅ 完成 |
| 阶段 3 | 文件上传与存储服务                         | ✅ 完成 |
| 阶段 4 | 文件解析层（PDF/Excel/Word/OCR）         | ✅ 完成 |
| 阶段 5 | RAG 知识库（Chunker/Embedding/Milvus） | ✅ 完成 |
| 阶段 6 | AI 字段抽取引擎（Spring AI + Qwen-Plus）  | ✅ 完成 |
| 阶段 7 | AI 结果可靠性（JSON Schema + Retry）     | ✅ 完成 |
| 阶段 8 | 异步任务 + SSE 进度推送 + Redis           | ✅ 完成 |

### 开发约定

- **严格按阶段开发，不一次生成全部代码**。
- 每阶段完成后：输出代码 → 解释设计 → 给出运行方式 → 等待用户确认后进入下一阶段。
- 企业级分层：Controller-Service-Repository。
- 方法不超过 50 行；核心代码加注释；不写 Demo 垃圾代码。
- 保证后续可扩展 Agent 能力。
- 字段类型须支持未来 AI Prompt 生成（FieldType 携带 `jsonSchemaType`）。
- 数据访问层包名必须为 `repository`。

---

## 4. 系统架构

### 同步链路（阶段 6-7）

```
POST /fill/{formId}?fileId=...
   ↓
FieldExtractorService.extract
   ↓
DocumentIngestionService.ingest (幂等)
   ↓ PARSING → VECTORING → SUCCESS
FileParser (PDF/Excel/Word) → ParserDocument
   ↓
DocumentChunker.chunk (JTokkit 滑动窗口)
   ↓
VectorStoreService.store (Milvus)
   ↓
FieldQueryGenerator.generate (按字段生成 query)
   ↓
VectorStoreService.search (fileId 过滤去重)
   ↓
ExtractionPromptBuilder.buildSystemPrompt + buildUserPrompt
   ↓ (含动态 JSON Schema)
ChatModel.call (Qwen-Plus)
   ↓
ObjectMapper.readValue → Map<String,Object>
   ↓
FieldSchemaValidator.validate (类型转换 + 错误分类)
   ↓ 失败 → Retry 反馈 → buildRetryFeedback 注入下一轮 prompt
ExtractionResult (values + errors + attemptsUsed)
```

### 异步链路（阶段 8）

```
POST /task
   ↓ (formId + fileId)
ParseTaskService.start
   ↓ 校验 form/file → 生成 taskId(UUID) → 发布初始 0% → 异步触发
AsyncParseExecutor.run (@Async parseExecutor)
   ↓
DocumentIngestionService.ingest(callback)
   ├── PARSING 0%  → ProgressPublisher.publish
   └── VECTORING 50% → ProgressPublisher.publish
   ↓ 80% EXTRACTING
FieldExtractorService.extract
   ↓ 100% SUCCESS
ProgressPublisher.publish (携带 ExtractionResult)
   ↓ 双写 Redis
   ├── 快照 task:progress:{taskId} (TTL 3h，供断线重连)
   └── Pub/Sub channel "task:progress"
   ↓
RedisMessageListenerContainer
   ↓
ProgressMessageListener.onMessage (反序列化为 TaskProgress)
   ↓
SseEmitterManager.send (按 taskId 路由到对应 SSE 连接)
   ↓
GET /task/progress/{taskId} (前端 SSE 订阅)
   ↓ 终态自动 complete emitter
```

---

## 5. 数据库设计

> 文件：[schema.sql](file:///d:/project/aiFileParser/src/main/resources/db/schema.sql)，手动执行

### form_definition（表单定义）

| 列           | 类型           | 说明             |
|-------------|--------------|----------------|
| id          | BIGINT       | 雪花算法主键         |
| form_name   | VARCHAR(100) | 表单名称           |
| description | VARCHAR(500) | 表单描述           |
| create_time | DATETIME     | 自动填充           |
| update_time | DATETIME     | 自动填充           |
| deleted     | TINYINT      | 逻辑删除：0 正常 1 删除 |

### form_field_definition（表单字段定义）

| 列                                   | 类型           | 说明                                  |
|-------------------------------------|--------------|-------------------------------------|
| id                                  | BIGINT       | 雪花算法主键                              |
| form_id                             | BIGINT       | 所属表单 ID                             |
| field_name                          | VARCHAR(100) | 字段中文名                               |
| field_code                          | VARCHAR(64)  | 字段编码，表单内唯一（`uk_form_field_code`）    |
| field_type                          | VARCHAR(20)  | STRING/INTEGER/DECIMAL/DATE/BOOLEAN |
| required                            | TINYINT      | 0 否 1 是                             |
| description                         | VARCHAR(500) | 字段描述，供 AI 理解                        |
| sort                                | INT          | 排序号，升序                              |
| create_time / update_time / deleted | -            | 同上                                  |

### file_record（文件记录）

| 列                                   | 类型                              | 说明                                                   |
|-------------------------------------|---------------------------------|------------------------------------------------------|
| id                                  | BIGINT                          | 雪花算法主键                                               |
| file_name                           | VARCHAR(255)                    | 原始文件名                                                |
| file_type                           | VARCHAR(20)                     | PDF/EXCEL/WORD/OTHER                                 |
| file_path                           | VARCHAR(500)                    | 存储相对路径（yyyy/MM/uuid.ext）                             |
| status                              | VARCHAR(20)                     | UPLOADED/PARSING/VECTORING/EXTRACTING/SUCCESS/FAILED |
| create_time / update_time / deleted | -                               | 同上                                                   |
| 索引                                  | idx_file_status / idx_file_type | -                                                    |

---

## 6. 配置要点（application.yml）

| 命名空间                                  | 关键配置                                                              |
|---------------------------------------|-------------------------------------------------------------------|
| server                                | port=8080, context-path=/aifp, multipart.max-file-size=100MB      |
| spring.datasource                     | MySQL + HikariCP（max 20, min-idle 5）                              |
| spring.ai.dashscope                   | api-key=${AI_DASHSCOPE_API_KEY}, model=qwen-plus, temperature=0.3 |
| spring.ai.dashscope.embedding.options | model=text-embedding-v2（1536 维）                                   |
| spring.ai.vectorstore.milvus          | collection=aifp_doc_chunks, dimension=1536, IVF_FLAT/COSINE       |
| spring.data.redis                     | Lettuce 池（max-active 16）                                          |
| rag.chunk                             | size=800, overlap=200                                             |
| rag.retrieve                          | topK=5                                                            |
| rag.extract.retry                     | max-attempts=2（含首次共 3 次 LLM 调用）                                   |
| task.sse                              | timeout-ms=1800000（30 分钟）                                         |
| task.progress                         | snapshot-ttl-hours=3                                              |
| mybatis-plus                          | id-type=assign_id, logic-delete-field=deleted                     |
| file                                  | upload-dir=${FILE_UPLOAD_DIR:./uploads}                           |

---

## 7. 包结构与完整文件清单

```
src/main/java/com/aifp/aiagent/
├── AiFileParserApplication.java                  # 启动入口
├── common/                                       # 统一返回与状态码
│   ├── Result.java
│   └── ResultCode.java
├── config/                                       # 配置类
│   ├── AsyncConfig.java
│   ├── MybatisMetaObjectHandler.java
│   ├── MybatisPlusConfig.java
│   └── RedisConfig.java
├── controller/                                   # REST 接口
│   ├── FileController.java
│   ├── FillController.java
│   ├── FormController.java
│   ├── HealthController.java
│   └── TaskController.java
├── document/                                     # 解析后统一文档模型
│   ├── ParserDocument.java
│   └── ParserDocumentMetadata.java
├── dto/                                          # 数据传输对象
│   ├── ExtractionResult.java
│   ├── FieldError.java
│   ├── FileRecordVO.java
│   ├── FileUploadVO.java
│   ├── FormCreateDTO.java
│   ├── FormFieldCreateDTO.java
│   ├── FormFieldVO.java
│   ├── FormVO.java
│   ├── TaskProgress.java
│   ├── TaskStartRequest.java
│   └── TaskStartVO.java
├── entity/                                       # 数据库实体
│   ├── BaseEntity.java
│   ├── FileRecord.java
│   ├── FormDefinition.java
│   ├── FormFieldDefinition.java
│   └── enums/
│       ├── FieldType.java
│       ├── FileStatus.java
│       └── FileType.java
├── exception/                                    # 异常处理
│   ├── BusinessException.java
│   └── GlobalExceptionHandler.java
├── parser/                                       # 文件解析器（策略模式）
│   ├── FileParser.java
│   ├── FileParserRegistry.java
│   ├── PdfParser.java
│   ├── ExcelParser.java
│   ├── WordParser.java
│   └── ocr/
│       ├── OcrParser.java
│       └── PaddleOcrParser.java
├── rag/                                          # RAG 知识库
│   ├── DocumentChunker.java
│   ├── DocumentIngestionService.java
│   ├── EmbeddingService.java
│   ├── ExtractionPromptBuilder.java
│   ├── FieldQueryGenerator.java
│   ├── FieldSchemaValidator.java
│   ├── ProgressCallback.java
│   ├── VectorStoreService.java
│   └── impl/
│       ├── DocumentIngestionServiceImpl.java
│       ├── EmbeddingServiceImpl.java
│       └── VectorStoreServiceImpl.java
├── repository/                                   # 数据访问层（Mapper）
│   ├── FileRecordMapper.java
│   ├── FormDefinitionMapper.java
│   └── FormFieldDefinitionMapper.java
├── service/                                      # 业务服务接口
│   ├── FieldExtractorService.java
│   ├── FileService.java
│   ├── FormService.java
│   ├── ParseTaskService.java
│   ├── storage/
│   │   ├── FileStorageService.java
│   │   └── LocalFileStorageService.java
│   └── impl/
│       ├── FieldExtractorServiceImpl.java
│       ├── FileServiceImpl.java
│       ├── FormServiceImpl.java
│       └── ParseTaskServiceImpl.java
└── task/                                         # 异步任务组件
    ├── AsyncParseExecutor.java
    ├── ProgressMessageListener.java
    ├── ProgressPublisher.java
    └── SseEmitterManager.java
```

---

## 8. 类详情（按包分组）

### 8.1 根包

#### AiFileParserApplication

- **类型**：启动类
- **注解**：`@SpringBootApplication` `@MapperScan("com.aifp.aiagent.repository")` `@EnableTransactionManagement`
- **职责**：应用引导、Mapper 扫描、事务开启
- **方法**：
    - `static void main(String[] args)` —— 入口

---

### 8.2 common 包

#### Result\<T>

- **类型**：统一返回封装
- **字段**：`Integer code`、`String message`、`T data`、`long timestamp`
- **静态工厂**：
    - `static <T> Result<T> success()`
    - `static <T> Result<T> success(T data)`
    - `static <T> Result<T> success(T data, String message)`
    - `static <T> Result<T> fail(ResultCode rc)`
    - `static <T> Result<T> fail(ResultCode rc, String message)`
    - `static <T> Result<T> fail(Integer code, String message)`
- **实例方法**：
    - `boolean isSuccess()` —— code=200

#### ResultCode（枚举）

- **类型**：enum
- **字段**：`Integer code`、`String message`
- **常量**：
    - `SUCCESS(200, "操作成功")`
    - `PARAM_ERROR(400, "参数错误")`
    - `PARAM_VALID_ERROR(40001, "参数校验失败")`
    - `UNAUTHORIZED(401)` / `FORBIDDEN(403)` / `NOT_FOUND(404)`
    - `INTERNAL_ERROR(500)` / `SERVICE_UNAVAILABLE(503)`
    -
    文件解析：`FILE_PARSE_ERROR(2001)` / `FILE_TYPE_NOT_SUPPORT(2002)` / `FILE_UPLOAD_ERROR(2003)` / `FILE_NOT_FOUND(2004)`
    - AI 调用：`AI_INVOKE_ERROR(3001)` / `AI_RESPONSE_PARSE_ERROR(3002)`
    - 向量检索：`VECTOR_STORE_ERROR(4001)` / `VECTOR_RETRIEVE_ERROR(4002)`
    -
    动态表单：`FORM_NOT_FOUND(5001)` / `FIELD_CODE_DUPLICATE(5002)` / `FIELD_NOT_FOUND(5003)` / `FORM_FIELD_EMPTY(5004)`

---

### 8.3 config 包

#### AsyncConfig

- **类型**：`@Configuration` `@EnableAsync`
- **职责**：提供 parseExecutor 线程池
- **Bean**：
    - `@Bean("parseExecutor") Executor parseExecutor()` —— core 2 / max 4 / queue 100 / CallerRunsPolicy / 等待关闭 30s

#### MybatisMetaObjectHandler

- **类型**：`@Component` implements `MetaObjectHandler`
- **职责**：审计字段自动填充
- **方法**：
    - `void insertFill(MetaObject)` —— 填充 createTime + updateTime
    - `void updateFill(MetaObject)` —— 填充 updateTime

#### MybatisPlusConfig

- **类型**：`@Configuration`
- **Bean**：
    - `MybatisPlusInterceptor mybatisPlusInterceptor()` —— PaginationInnerInterceptor(MySQL) +
      OptimisticLockerInnerInterceptor

#### RedisConfig

- **类型**：`@Configuration` `@RequiredArgsConstructor`
- **依赖**：`RedisConnectionFactory`、`ProgressMessageListener`
- **Bean**：
    - `RedisSerializer<Object> redisValueSerializer()` —— `GenericJackson2JsonRedisSerializer`（保留 `@class` 类型信息）
    - `RedisTemplate<String, Object> redisTemplate(...)` —— String key + JSON value
    - `RedisMessageListenerContainer redisMessageListenerContainer()` —— 订阅 `ProgressPublisher.CHANNEL`

---

### 8.4 controller 包

所有 Controller 路径前缀均为 `/aifp`（context-path）。

#### FileController（前缀 `/file`）

- **依赖**：`FileService`
- **接口**：
    - `POST /file/upload` —— `Result<FileUploadVO> upload(@RequestParam("file") MultipartFile file)`
  - `GET /file/page` —— `Result<PageResult<FileRecordVO>> page(@Valid PageQuery query)`（按上传时间倒序）

#### FillController（前缀 `/fill`）

- **依赖**：`FieldExtractorService`
- **接口**：
    - `POST /fill/{formId}?fileId=...` —— `Result<ExtractionResult> fill(@PathVariable Long formId, @RequestParam Long fileId)`

#### FormController（前缀 `/form`）

- **依赖**：`FormService`
- **接口**：
    - `POST /form/create` —— `Result<Long> create(@Valid @RequestBody FormCreateDTO dto)`
    - `GET /form/{id}` —— `Result<FormVO> get(@PathVariable Long id)`
    - `POST /form/{id}/field` —— `Result<Long> addField(@PathVariable Long id, @Valid @RequestBody FormFieldCreateDTO dto)`
    - `DELETE /form/{id}/field/{fieldId}` —— `Result<Void> deleteField(...)`

#### HealthController（前缀 `/health`）

- **接口**：
    - `GET /health/ping` —— `Result<Map<String,Object>> ping()` —— 返回 app/status/timestamp

#### TaskController（前缀 `/task`）

- **依赖**：`ParseTaskService`、`SseEmitterManager`、`ProgressPublisher`
- **配置字段**：`@Value("${task.sse.timeout-ms:1800000}") long sseTimeoutMs`
- **接口**：
    - `POST /task` —— `Result<TaskStartVO> start(@Valid @RequestBody TaskStartRequest request)`
    - `GET /task/progress/{taskId}` —— `SseEmitter progress(@PathVariable String taskId)` (produces `text/event-stream`)
- **私有方法**：
    - `void sendSnapshotIfPresent(String taskId, SseEmitter emitter)` —— 首帧推送快照，终态自动 complete

---

### 8.5 document 包

#### ParserDocument

- **类型**：`@Data` implements `Serializable`
- **字段**：`String content`、`ParserDocumentMetadata metadata`

#### ParserDocumentMetadata

- **类型**：`@Data` `@Builder` implements `Serializable`
- **字段**：
    - `String fileName`
    - `Integer page` —— 语义按类型差异：PDF=页数 / Excel=工作表数 / Word=段落数
    - `FileType type`
    - `Long fileId` —— 由 IngestionService 注入，供 chunk 元数据按文件过滤

---

### 8.6 dto 包

#### ExtractionResult

- **字段**：
    - `Map<String, Object> values` —— 类型化字段值
    - `List<FieldError> errors` —— 剩余字段级错误
    - `int attemptsUsed` —— LLM 调用次数

#### FieldError

- **字段**：`String fieldCode`、`String errorType`（MISSING/TYPE/FORMAT）、`String message`、`Object rawValue`
- **构造**：`@NoArgsConstructor` `@AllArgsConstructor`

#### FileRecordVO

- **字段**：fileId / fileName / fileType / filePath / status / createTime / updateTime

#### FileUploadVO

- **字段**：fileId / fileName / fileType / filePath / status / createTime

#### FormVO

- **字段**：formId / formName / description / createTime / updateTime / `List<FormFieldVO> fields`

#### FormFieldVO

- **字段**：fieldId / fieldName / fieldCode / fieldType / required / description / sort

#### FormCreateDTO

- **校验**：
    - `@NotBlank @Size(max=100) String formName`
    - `@Size(max=500) String description`
    - `@Valid List<FormFieldCreateDTO> fields`

#### FormFieldCreateDTO

- **校验**：
    - `@NotBlank @Size(max=100) String fieldName`
    - `@NotBlank @Size(max=64) @Pattern(regexp="^[a-zA-Z][a-zA-Z0-9_]*$") String fieldCode`
    - `@NotNull FieldType fieldType`
    - `Boolean required`（缺省 false）
    - `@Size(max=500) String description`
    - `Integer sort`（缺省 0）

#### TaskStartRequest

- **校验**：
    - `@NotNull(message="表单ID不能为空") Long formId`
    - `@NotNull(message="文件ID不能为空") Long fileId`

#### TaskStartVO

- **字段**：taskId / fileId / formId / createTime
- **构造**：`@AllArgsConstructor`

#### TaskProgress

- **类型**：`@Data` implements `Serializable`，SSE 事件载荷 + Redis 快照对象
- **字段**：
    - `String taskId`
    - `Long fileId`
    - `Long formId`
    - `String status` —— PARSING/VECTORING/EXTRACTING/SUCCESS/FAILED
    - `int percent` —— 0/50/80/100，失败为 -1
    - `String message`
    - `Object result` —— 100% 时携带 `ExtractionResult`
    - `long timestamp`
- **构造**：无参 + 全参（自动填 timestamp）
- **方法**：
    - `TaskProgress with(String status, int percent, String message, Object result)` —— 拷贝工厂，复用
      taskId/fileId/formId
    - `boolean isTerminal()` —— percent==100 或 status==FAILED

---

### 8.7 entity 包

#### BaseEntity（abstract）

- **字段**：
    - `@TableId(type=IdType.ASSIGN_ID) Long id`
    - `@TableField(fill=INSERT) LocalDateTime createTime`
    - `@TableField(fill=INSERT_UPDATE) LocalDateTime updateTime`
    - `@TableLogic Integer deleted`

#### FileRecord（表 file_record）

- **继承**：BaseEntity
- **字段**：`String fileName`、`FileType fileType`、`String filePath`、`FileStatus status`

#### FormDefinition（表 form_definition）

- **继承**：BaseEntity
- **字段**：`String formName`、`String description`

#### FormFieldDefinition（表 form_field_definition）

- **继承**：BaseEntity
- **字段
  **：`Long formId`、`String fieldName`、`String fieldCode`、`FieldType fieldType`、`Boolean required`、`String description`、`Integer sort`

#### FieldType（枚举）

- **字段**：`@EnumValue String code`、`String label`、`String jsonSchemaType`
- **常量**：
    - STRING("STRING","字符串","string")
    - INTEGER("INTEGER","整数","integer")
    - DECIMAL("DECIMAL","小数","number")
    - DATE("DATE","日期","string")
    - BOOLEAN("BOOLEAN","布尔","boolean")

#### FileStatus（枚举）

- **字段**：`@EnumValue String code`、`String label`
- **常量**：UPLOADED / PARSING / VECTORING / EXTRACTING / SUCCESS / FAILED
- **流转**：UPLOADED → PARSING → VECTORING → EXTRACTING → SUCCESS / FAILED

#### FileType（枚举）

- **字段**：`@EnumValue String code`、`String label`、`Set<String> extensions`
- **常量**：
    - PDF("PDF","PDF文档",{"pdf"})
    - EXCEL("EXCEL","Excel表格",{"xlsx","xls"})
    - WORD("WORD","Word文档",{"docx","doc"})
    - TXT("TXT","txt文件",{"txt"})
    - OTHER("OTHER","其他类型",Set.of())
- **静态方法**：
    - `static FileType ofExtension(String extension)` —— 扩展名匹配，未匹配返回 null

---

### 8.8 exception 包

#### BusinessException

- **继承**：`RuntimeException`
- **字段**：`Integer code`（对应 ResultCode）
- **构造**：
    - `BusinessException(ResultCode rc)`
    - `BusinessException(ResultCode rc, String message)`
    - `BusinessException(ResultCode rc, String message, Throwable cause)`
    - `BusinessException(String message)` —— code 默认 INTERNAL_ERROR

#### GlobalExceptionHandler

- **类型**：`@RestControllerAdvice`
- **@ExceptionHandler**：
    - `BusinessException` → `Result.fail(code, msg)`，warn 日志
    - `MethodArgumentNotValidException` → 聚合字段错误，code=40001
    - `BindException` → 同上
    - `ConstraintViolationException` → code=40001
    - `MissingServletRequestParameterException` → code=400
    - `MethodArgumentTypeMismatchException` → code=400
    - `HttpMessageNotReadableException` → ResponseEntity 400
    - `Exception`（兜底）→ code=500，error 日志

---

### 8.9 parser 包

#### FileParser（接口）

- **方法**：
    - `FileType supportedType()`
    - `ParserDocument parse(File file)`

#### FileParserRegistry

- **类型**：`@Component`
- **依赖注入**：`List<FileParser>` → 构造 `Map<FileType, FileParser>`
- **方法**：
    - `FileParser get(FileType type)` —— 无可用解析器抛 `FILE_TYPE_NOT_SUPPORT`

#### PdfParser

- **类型**：`@Component` implements `FileParser`
- **支持**：`FileType.PDF`
- **库**：Apache PDFBox `Loader.loadPDF` + `PDFTextStripper`
- **方法**：`ParserDocument parse(File)` —— 元数据 page=文档页数；扫描版 PDF 内容为空（后续 OCR 接管）

#### ExcelParser

- **支持**：`FileType.EXCEL`
- **库**：Apache POI `WorkbookFactory`（自动识别 xls/xlsx）
- **方法**：`ParserDocument parse(File)` —— 按"工作表→行→单元格"拼装，分隔符 `\n\n` / `\n` / `\t`，元数据 page=工作表数

#### WordParser

- **支持**：`FileType.WORD`
- **库**：Apache POI `XWPFDocument`（仅 .docx）
- **方法**：`ParserDocument parse(File)` —— 遍历段落抽取文本，元数据 page=段落数；.doc 抛 `FILE_PARSE_ERROR`

#### OcrParser（接口）

- **方法**：`String recognize(File file)` —— 预留 OCR 接入点

#### PaddleOcrParser

- **类型**：`@Component` implements `OcrParser`
- **行为**：抛 `UnsupportedOperationException`，作为后续 PaddleOCR 集成占位 bean

---

### 8.10 rag 包

#### DocumentChunker

- **类型**：`@Component`
- **分词器**：JTokkit `EncodingType.CL100K_BASE`
- **配置**：`@Value("${rag.chunk.size:800}") int chunkSize`、`@Value("${rag.chunk.overlap:200}") int overlap`
- **方法**：
    - `List<Document> chunk(ParserDocument doc)` —— token 滑动窗口切片
- **元数据**：fileName / fileType / page / fileId / chunkIndex / totalChunks

#### DocumentIngestionService（接口）

- **方法**：
    - `void ingest(Long fileId)` —— 幂等入库（已 SUCCESS 则跳过）
    - `void ingest(Long fileId, ProgressCallback callback)` —— 带阶段回调版本

#### ProgressCallback（@FunctionalInterface）

- **方法**：`void onStageStart(String stageCode)` —— 阶段码 PARSING/VECTORING

#### EmbeddingService（接口）

- **方法**：
    - `float[] embed(String text)`
    - `List<float[]> embedBatch(List<String> texts)`

#### VectorStoreService（接口）

- **方法**：
    - `void store(List<Document> chunks)`
    - `List<Document> search(String query, int topK)`
    - `List<Document> search(String query, int topK, String filterExpression)` —— 支持按 fileId 过滤避免跨文件污染

#### FieldQueryGenerator

- **类型**：`@Component`
- **模板**：`"请从文档中提取%s。"`
- **方法**：
    - `String generate(FormFieldVO field)` —— fieldName 为主，description 非空则追加

#### ExtractionPromptBuilder

- **类型**：`@Component`
- **方法**：
    - `String buildSystemPrompt(List<FormFieldVO> fields)` —— 不带反馈
    - `String buildSystemPrompt(List<FormFieldVO> fields, String feedback)` —— 含 Retry 反馈
    - `String buildRetryFeedback(List<FieldError> errors)` —— 失败字段列表反馈
    - `String buildUserPrompt(List<Document> chunks)` —— 拼接 chunks 内容
- **schema 格式**：`{"fieldCode":"type,必填?,fieldName,description?"}`
- **特性**：类型映射复用 `FieldType.getJsonSchemaType()`，无硬编码；JSON 字符串自动转义

#### FieldSchemaValidator

- **类型**：`@Component`
- **错误常量**：`ERR_MISSING="MISSING"` / `ERR_TYPE="TYPE"` / `ERR_FORMAT="FORMAT"`
- **常量**：`BigDecimal WAN=10000` / `YI=100000000`；4 种 `DateTimeFormatter`（yyyy-MM-dd / yyyy/MM/dd / yyyy年MM月dd日 /
  yyyyMMdd）
- **方法**：
    - `ValidationResult validate(Map<String,Object> raw, List<FormFieldVO> fields)` —— 类型化 coerced + 字段错误 errors
- **智能转换**：
    - INTEGER：`parseWithUnit` 支持「万/亿」单位 + 千分位逗号，`longValueExact` 防小数
    - DECIMAL：`stripTrailingZeros`
    - BOOLEAN：true/false/1/0
    - DATE：按 4 种格式优先级尝试
- **内部类
  ** `ValidationResult`（`@Data @RequiredArgsConstructor`）：`Map<String,Object> coerced` + `List<FieldError> errors` + `boolean hasErrors()`

---

### 8.11 rag.impl 包

#### DocumentIngestionServiceImpl

- **依赖**：FileService / FileStorageService / FileParserRegistry / DocumentChunker / VectorStoreService
- **方法**：
    - `void ingest(Long fileId)` —— 委托 `ingest(fileId, null)`
    - `void ingest(Long fileId, ProgressCallback callback)` —— 幂等校验 → `doIngest`
- **流程**：`getById` → PARSING(回调) → 解析 → setFileId → VECTORING(回调) → chunk → store → SUCCESS
- **失败处理**：`markFailed` 标记 FAILED，吞掉二次异常避免覆盖原始异常
- **私有方法**：`doIngest` / `notifyStage` / `parseDocument` / `toFile` / `markFailed`

#### VectorStoreServiceImpl

- **依赖**：Spring AI `VectorStore`（Milvus 自动装配）
- **方法**：
    - `void store(List<Document>)` —— `vectorStore.add`
    - `List<Document> search(String, int)` —— 委托三参版本
    - `List<Document> search(String, int, String filterExpression)` —— `SearchRequest.builder` + `filterExpression`

#### EmbeddingServiceImpl

- **依赖**：Spring AI `EmbeddingModel`（DashScope）
- **方法**：
    - `float[] embed(String text)` —— `embeddingModel.embed`
    - `List<float[]> embedBatch(List<String>)` —— `embedForResponse` 转换为 List<float[]>

---

### 8.12 repository 包

> 所有 Mapper 继承 `BaseMapper<T>`，无自定义 SQL。

- `FileRecordMapper extends BaseMapper<FileRecord>`
- `FormDefinitionMapper extends BaseMapper<FormDefinition>`
- `FormFieldDefinitionMapper extends BaseMapper<FormFieldDefinition>`

---

### 8.13 service 包

#### FileService（接口）

- **方法**：
    - `FileUploadVO upload(MultipartFile file)`
    - `void updateStatus(Long id, FileStatus status)`
    - `FileRecordVO getById(Long id)`

#### FormService（接口）

- **方法**：
    - `Long createForm(FormCreateDTO dto)` —— 事务，表单头+可选字段批插
    - `FormVO getFormById(Long id)` —— 字段按 sort 升序
    - `Long addField(Long formId, FormFieldCreateDTO dto)`
    - `void deleteField(Long formId, Long fieldId)` —— 软删

#### FieldExtractorService（接口）

- **方法**：
    - `ExtractionResult extract(Long formId, Long fileId)` —— 含 Schema 校验 + Retry 反馈

#### ParseTaskService（接口）

- **方法**：
    - `TaskStartVO start(Long formId, Long fileId)` —— 校验 + 生成 taskId + 发布初始 0% + 触发异步执行

---

### 8.14 service.impl 包

#### FileServiceImpl

- **依赖**：`FileRecordMapper`、`FileStorageService`
- **方法**：
    - `@Transactional FileUploadVO upload(MultipartFile)` —— 校验非空 → 解析 FileType → 存储 → 写 file_record(UPLOADED)
    - `@Transactional void updateStatus(Long, FileStatus)` —— 不存在抛 FILE_NOT_FOUND
    - `FileRecordVO getById(Long)` —— 不存在抛 FILE_NOT_FOUND
- **私有方法**：`validateNotEmpty` / `resolveType` / `extractExtension` / `toUploadVO` / `toRecordVO`

#### FormServiceImpl

- **依赖**：`FormDefinitionMapper`、`FormFieldDefinitionMapper`
- **方法**：
    - `@Transactional Long createForm(FormCreateDTO)` —— 批内 fieldCode 唯一校验 → 插表单 → 循环插字段（带 fallback
      order）
    - `FormVO getFormById(Long)` —— 不存在抛 FORM_NOT_FOUND；字段用 `LambdaQueryWrapper` 按 sort 升序
    - `@Transactional Long addField(Long, FormFieldCreateDTO)` —— `ensureFormExists` + `ensureFieldCodeNotDuplicate`
    - `@Transactional void deleteField(Long, Long)` —— 校验 field.formId 匹配后软删
- **私有方法
  **：`ensureFormExists` / `ensureFieldCodeNotDuplicate` / `checkFieldCodeUniqueInBatch` / `toEntity` / `toFormVO` / `toFieldVO`

#### FieldExtractorServiceImpl

- **依赖**：DocumentIngestionService / FormService / FileService / VectorStoreService / FieldQueryGenerator /
  ExtractionPromptBuilder / FieldSchemaValidator / `ChatModel` / `ObjectMapper`
- **配置**：`@Value("${rag.retrieve.topK:5}") int topK`、`@Value("${rag.extract.retry.max-attempts:2}") int maxAttempts`
- **方法**：
    - `ExtractionResult extract(Long formId, Long fileId)` —— 主流程
- **私有方法**：
    - `ExtractionResult extractWithRetry(List<FormFieldVO>, List<Document>)` —— Retry 循环（最多 maxAttempts+1 次 LLM
      调用），无错即返回，有错反馈重试
    - `ExtractionResult buildResult(Map, List<FieldError>, int used)`
    - `List<FormFieldVO> loadFields(Long)` —— 表单不存在/字段为空抛异常
    - `List<Document> retrieveAndMerge(List<FormFieldVO>, String filter)` —— 按字段检索 + Document.id 去重保序
    - `String callChatModel(String, String)` —— SystemMessage + UserMessage 调 ChatModel
    - `Map<String,Object> parseJson(String)` —— 剥离 markdown 围栏 + `objectMapper.readValue`
    - `String stripCodeFence(String)` —— 剥离 ```json...```

#### ParseTaskServiceImpl

- **依赖**：FormService / FileService / ProgressPublisher / AsyncParseExecutor
- **方法**：
    - `TaskStartVO start(Long formId, Long fileId)` —— 校验存在 → UUID taskId → `publishInitial`
      0% → `asyncParseExecutor.run`
- **私有方法**：`void publishInitial(String taskId, Long formId, Long fileId)`

---

### 8.15 service.storage 包

#### FileStorageService（接口）

- **方法**：
    - `String store(MultipartFile file, FileType fileType)` —— 返回相对路径
    - `Resource load(String relativePath)`
    - `void delete(String relativePath)`

#### LocalFileStorageService

- **类型**：`@Service`
- **配置**：`@Value("${file.upload-dir}") String uploadDir`
- **存储规则**：`{upload-dir}/yyyy/MM/{uuid无横线}.{ext}`
- **@PostConstruct** `void init()` —— 创建根目录
- **方法**：实现接口三方法；`load` 返回 `FileSystemResource`；`delete` 用 `Files.deleteIfExists`
- **私有方法**：`String extractExtension(String fileName)`

---

### 8.16 task 包

#### AsyncParseExecutor

- **类型**：`@Service` `@RequiredArgsConstructor`
- **依赖**：DocumentIngestionService / FieldExtractorService / ProgressPublisher
- **方法**：
    - `@Async("parseExecutor") void run(String taskId, Long formId, Long fileId)` —— 完整流水线
- **流程**：
    - `ingestWithProgress(base, fileId)` —— 发布 0% PARSING / 50% VECTORING
    - 发布 80% EXTRACTING
    - `fieldExtractorService.extract` —— 注意 extract 内部幂等 ingest 会因 SUCCESS 跳过
    - 发布 100% SUCCESS（携带 ExtractionResult）
    - 异常 → 发布 -1 FAILED（message 含 e.getMessage）
- **私有方法**：`void ingestWithProgress(TaskProgress base, Long fileId)` —— 用 lambda 回调触发阶段进度

#### ProgressPublisher

- **类型**：`@Component` `@RequiredArgsConstructor`
- **常量
  **：`public static final String CHANNEL = "task:progress"`、`private static final String SNAPSHOT_KEY_PREFIX = "task:progress:"`
- **依赖**：`RedisTemplate<String, Object>`
- **配置**：`@Value("${task.progress.snapshot-ttl-hours:3}") int snapshotTtlHours`
- **方法**：
    - `void publish(TaskProgress progress)` ——
      双写：`opsForValue().set(key, progress, ttl)` + `convertAndSend(CHANNEL, progress)`；失败仅 warn 不抛
    - `TaskProgress getSnapshot(String taskId)` —— 读取快照，异常返回 null
- **私有方法**：`String snapshotKey(String taskId)`

#### SseEmitterManager

- **类型**：`@Component`
- **字段**：`ConcurrentMap<String, SseEmitter> emitters`
- **方法**：
    - `SseEmitter register(String taskId, long timeout)` —— 创建 emitter + 绑定 onCompletion/onTimeout/onError 回调自动移除
    - `protected SseEmitter createEmitter(long timeout)` —— 工厂方法，测试可覆盖注入 mock
    - `void send(String taskId, TaskProgress progress)` —— 路由到对应连接，失败移除
    - `void complete(String taskId)` —— 主动完成并移除
    - `boolean isActive(String taskId)` —— 诊断用
- **私有方法**：`void remove(String taskId)`

#### ProgressMessageListener

- **类型**：`@Component` `@RequiredArgsConstructor` implements `MessageListener`
- **依赖**：`RedisSerializer<Object> valueSerializer`（复用 RedisTemplate 值序列化器保证对称反序列化）、`SseEmitterManager`
- **方法**：
    - `void onMessage(Message message, byte[] pattern)` —— 反序列化为
      TaskProgress → `sseEmitterManager.send(taskId, progress)`；终态自动 `complete`

---

## 9. 关键设计约定

### 统一返回

- 所有 Controller 返回 `Result<T>`，字段 code/message/data/timestamp。
- 静态工厂构造；`isSuccess()` 判 code=200。
- 新增模块错误码需在 `ResultCode` 追加对应段（2xxx 文件 / 3xxx AI / 4xxx 向量 / 5xxx 表单）。

### Long 型 ID 序列化（强约定）

- 雪花 ID 超出 JS `Number.MAX_SAFE_INTEGER`，所有 VO/DTO 中 Long 型 ID（formId/fileId/fieldId）必须标注
  `@JsonSerialize(using = ToStringSerializer.class)` 以字符串传输（TaskProgress/FormVO/FileRecordVO/FormFieldVO/FileUploadVO
  等均已处理）。

### 异常处理

- 业务异常 `BusinessException(ResultCode.X, "msg")` 由 `GlobalExceptionHandler` 统一转 `Result.fail`。
- `@Valid` 校验失败 → `MethodArgumentNotValidException` → code=40001。
- 不要在 Controller 手写 try-catch，统一抛 `BusinessException`。

### MyBatis-Plus 约定

- 主键：`@TableId(type=IdType.ASSIGN_ID)` 雪花算法。
- 逻辑删除：全局 `logic-delete-field=deleted`，0 正常 1 删除，实体字段标 `@TableLogic`。
- 审计字段：`create_time`/`update_time`，由 `MybatisMetaObjectHandler` 自动填充。
- 实体继承 `BaseEntity`；表名下划线、字段驼峰自动映射。
- 枚举持久化用 `@EnumValue` 标在 code 字段上。
- 分页拦截器已配（PaginationInnerInterceptor + OptimisticLockerInnerInterceptor）。
- 不写 Mapper XML，CRUD 用 `BaseMapper` + `LambdaQueryWrapper`。

### AI / Qwen 配置

- 配置前缀 `spring.ai.dashscope`，模型 `qwen-plus`，`temperature: 0.3`（字段抽取偏稳定）。
- API Key 通过环境变量 `AI_DASHSCOPE_API_KEY` 注入（**启动必需**）。
- Embedding 模型 `text-embedding-v2`，1536 维，须与 Milvus `embedding-dimension` 一致。

### 异步与 SSE（阶段 8）

- 异步线程池 `parseExecutor`：core 2 / max 4 / queue 100 / `CallerRunsPolicy`（队列满由调用线程兜底）。
- `@Async` 代理生效需跨 bean 调用：`ParseTaskServiceImpl` → `AsyncParseExecutor.run`。
- Redis 双写策略：快照键 `task:progress:{taskId}`（TTL 3h，供断线重连） + Pub/Sub channel `task:progress`（实时推送）。
- 序列化对称性：发布端与订阅端必须复用同一 `RedisSerializer<Object>`，`GenericJackson2JsonRedisSerializer` 保留 `@class`
  类型信息。
- SSE 终态处理：`TaskProgress.isTerminal()` 触发 `sseEmitterManager.complete`，避免连接泄漏。
- 进度发布失败仅 warn 不抛，避免影响主流程。

### 测试约定

- Controller 测试用 **standalone MockMvc**（`MockMvcBuilders.standaloneSetup` + `@ExtendWith(MockitoExtension.class)`）。
    - 不用 `@WebMvcTest`：因 `@MapperScan` 在启动类上会触发 mapper 装配，切片测试无法离线启动。
- `@Mock Service` + `@InjectMocks Controller`，`@BeforeEach` 装配 MockMvc + `GlobalExceptionHandler` + Jackson 转换器。
- ObjectMapper 必须注册 `JavaTimeModule`（VO 含 `LocalDateTime`，否则序列化报 500）。
- void 方法 mock 用 `doThrow().when(...)` / `doAnswer().when(...)`，不能用 `when(...).thenX()`。
- `SseEmitter.send` 抛 `IOException`（checked），测试方法签名需 `throws Exception`。
- `SseEmitterManager` 提供 `protected SseEmitter createEmitter(long)` 工厂方法，测试子类可覆盖注入 mock。
- 测试覆盖：68 个用例全绿（18 个测试类）。

### 配置安全

- 所有敏感项用 `${ENV:默认值}` 占位符；DB/Qwen Key 走环境变量。
- context-path = `/aifp`，端口 8080。完整接口路径形如 `/aifp/form/...`。

### 日志

- `logback-spring.xml`：dev 控制台彩色输出；prod 按天滚动+压缩保留 30 天，错误日志单独归档。
- `%wEx` 转换词需手动注册 `ExtendedWhitespaceThrowableProxyConverter`（已修复，勿删）。

---

## 10. 测试体系

### 测试文件清单（src/test/java/com/aifp/aiagent/）

| 测试类                                        | 用例数    | 覆盖点                                                      |
|--------------------------------------------|--------|----------------------------------------------------------|
| controller/FileControllerTest              | 5      | 3 类型正向 + 2 异常路径（2002/2003）                               |
| controller/FillControllerTest              | 2      | 正向 + 业务异常转 Result                                        |
| controller/FormControllerTest              | 10     | 4 接口正向 + 校验失败(40001) + BusinessException(5001/5002/5003) |
| controller/TaskControllerTest              | 3      | POST /task 正向 + 参数校验 + SSE progress 直调                   |
| parser/FileParserRegistryTest              | 4      | 注册表 + 策略选择                                               |
| parser/PdfParserTest                       | 1      | PDF 文本抽取                                                 |
| parser/ExcelParserTest                     | 1      | Excel 多表抽取                                               |
| parser/WordParserTest                      | 1      | Word 段落抽取                                                |
| parser/ocr/PaddleOcrParserTest             | 1      | 占位抛 UnsupportedOperationException                        |
| rag/DocumentChunkerTest                    | 3      | 切片逻辑 + 边界                                                |
| rag/ExtractionPromptBuilderTest            | 4      | schema 生成 + Retry 反馈                                     |
| rag/FieldSchemaValidatorTest               | 9      | 类型转换 + 错误分类 + 万/亿单位                                      |
| rag/impl/DocumentIngestionServiceImplTest  | 3      | 入库主流程 + 异常 + 幂等                                          |
| service/impl/FieldExtractorServiceImplTest | 5      | 正常抽取 + Retry + 异常路径                                      |
| service/impl/ParseTaskServiceImplTest      | 3      | 任务创建 + UUID + 初始进度                                       |
| task/AsyncParseExecutorTest                | 2      | 完整流程 0→50→80→100 + 失败→FAILED                             |
| task/ProgressPublisherTest                 | 5      | 双写 + getSnapshot 命中/未命中/异常                               |
| task/SseEmitterManagerTest                 | 6      | register/send/complete/remove + IO 失败清理                  |
| **合计**                                     | **68** | **0 failures, 0 errors**                                 |

### 测试运行

```powershell
mvn test                                # 全量
mvn -Dtest=TaskControllerTest test      # 单个类
```

---

## 11. 运行方式

### 前置依赖

1. 本地 MySQL 8.0.46，建库 `aifileparser`，执行 `src/main/resources/db/schema.sql`。
2. 本地 Milvus（默认 localhost:19530，账号 root/milvus）。
3. 本地 Redis（默认 localhost:6379）。
4. 阿里云百炼平台获取 DashScope API Key。

### 启动

```powershell
$env:AI_DASHSCOPE_API_KEY="你的Key"
$env:DB_PASSWORD="你的MySQL密码"   # 若非默认 root/root
$env:MILVUS_PASSWORD="你的Milvus密码"  # 若非默认 milvus
mvn spring-boot:run
```

### 接口验证

```powershell
# 健康检查
curl http://localhost:8080/aifp/health/ping

# 创建表单
curl -X POST http://localhost:8080/aifp/form/create `
  -H "Content-Type: application/json" `
  -d '{"formName":"项目申报表","fields":[{"fieldName":"投资金额","fieldCode":"investAmount","fieldType":"DECIMAL","required":true}]}'

# 上传文件
curl -X POST http://localhost:8080/aifp/file/upload -F "file=@D:/test.pdf"

# 同步抽取
curl -X POST "http://localhost:8080/aifp/fill/{formId}?fileId={fileId}"

# 异步任务（推荐，带进度推送）
curl -X POST http://localhost:8080/aifp/task `
  -H "Content-Type: application/json" `
  -d '{"formId":{formId},"fileId":{fileId}}'

# 订阅 SSE 进度（浏览器/EventSource）
# http://localhost:8080/aifp/task/progress/{taskId}
```

### 已知阻塞

- **启动必需 `AI_DASHSCOPE_API_KEY`**，否则 `DashScopeAgentAutoConfiguration` 抛异常导致启动失败。
- Milvus 首次启动会自动建集合 `aifp_doc_chunks`（`initialize-schema: true`）。
- Redis 不可用时：同步 `/fill` 仍可用，但异步 `/task` 的进度推送会降级（`ProgressPublisher.publish` 仅 warn 不抛）。

---

## 12. 给新对话 AI 的工作指引

1. **先读本文件 + `.trae/documents/` 下的阶段规划文档**，再动代码。
2. **严格遵守"关键设计约定"**，尤其是包基址、统一返回、异常处理、MyBatis-Plus 约定、异步与 SSE 设计。
3. **修改前先读相关源码**，不要凭假设改代码；类详情见本文第 8 节。
4. **保持企业级分层**：Controller 不含业务逻辑，Service 接口与实现分离，Repository 仅继承 BaseMapper。
5. **方法不超过 50 行**，超长请拆私有方法（参考 `FieldExtractorServiceImpl` 的拆法）。
6. **核心代码加注释**，关键决策点（如幂等、Retry、对称反序列化）必须有注释说明。
7. **测试必须可离线运行**：standalone MockMvc + Mockito，不依赖 Spring 上下文/MySQL/Milvus/Redis。
8. **遇到启动/编译错误先查 surefire-reports 与实际报错根因**，不要盲目重试。
9. **计划模式（`/plan`）下**：必须先 Explore 代码再写计划，计划写入 `.trae/documents/`，用 `NotifyUser` 提交审批。
10. **每阶段完成后等待用户确认**，不要一次性推进多个阶段。
11. **敏感配置走环境变量**，不要硬编码 API Key/密码。
12. **新增字段类型**：在 `FieldType` 枚举追加，同步更新 `FieldSchemaValidator.coerce` switch
    分支与 `ExtractionPromptBuilder.buildFieldSchema`。
13. **新增文件解析器**：实现 `FileParser` 接口，`@Component` 注册，`FileParserRegistry` 自动收集。
14. **新增异步任务类型**：参考 `AsyncParseExecutor` 模式，独立 bean + `@Async("parseExecutor")`，跨 bean 调用以激活代理。
