# aiFileParser 项目上下文文档

> 本文档供新对话 AI 快速理解项目现状。基于历史开发对话压缩整理，反映截至文档生成时的代码状态。
> **最后更新**：阶段 2 完成 + FormController 测试完成

---

## 1. 项目概述

### 项目名称

aiFileParser —— 企业级 AI 文件自动解析自动填报系统

### 项目目标

实现：用户上传业务文件(PDF/Excel/Word) → 系统自动解析文件内容 → 根据用户自定义表单字段 → 调用 AI 模型提取对应字段 →
自动生成结构化数据 → 填充业务表单。

### 核心能力

1. 动态表单设计
2. 文件解析（PDF/Excel/Word/OCR 预留）
3. RAG 知识库
4. AI 字段抽取
5. JSON 结构化输出
6. 自动映射 Java 对象

---

## 2. 技术栈

| 类别    | 选型                            | 版本                                |
|-------|-------------------------------|-----------------------------------|
| 后端语言  | Java                          | 21                                |
| 框架    | Spring Boot                   | 3.5.13                            |
| AI 框架 | Spring AI + Spring AI Alibaba | Spring AI 1.0.0 / Alibaba 1.0.0.2 |
| 构建    | Maven                         | -                                 |
| 数据库   | MySQL                         | 8.0.46                            |
| ORM   | MyBatis-Plus                  | 3.5.16（+ mybatis-plus-jsqlparser） |
| AI 模型 | Qwen-Plus（阿里云百炼 DashScope）    | -                                 |
| 向量数据库 | Milvus                        | （阶段 5 接入，暂未引入依赖）                  |
| 文件解析  | PDFBox / POI                  | （阶段 4 引入，暂未引入依赖）                  |
| OCR   | PaddleOCR                     | （预留接口，阶段 4）                       |

---

## 3. 系统架构

```
用户上传文件
   ↓
File Service
   ↓
文件解析层 ( PDF Parser | Excel Parser | Word Parser | OCR Parser )
   ↓
Document 统一模型
   ↓
Semantic Chunker
   ↓
Embedding
   ↓
Milvus Vector Store
   ↓
Field Retriever
   ↓
Qwen-Plus
   ↓
JSON Schema
   ↓
DTO
   ↓
Entity
```

---

## 4. 开发阶段规划

| 阶段   | 内容                                | 状态    |
|------|-----------------------------------|-------|
| 阶段 1 | 项目骨架与基础配置                         | ✅ 完成  |
| 阶段 2 | 动态表单管理模块                          | ✅ 完成  |
| 阶段 3 | 文件上传与存储服务                         | ⏳ 待开发 |
| 阶段 4 | 文件解析层（PDF/Excel/Word/OCR）         | ⏳ 待开发 |
| 阶段 5 | RAG 知识库（Chunker/Embedding/Milvus） | ⏳ 待开发 |
| 阶段 6 | AI 字段抽取引擎（Spring AI + Qwen-Plus）  | ⏳ 待开发 |
| 阶段 7 | 自动映射与表单填充                         | ⏳ 待开发 |
| 阶段 8 | 集成编排与可扩展 Agent                    | ⏳ 待开发 |

### 开发约定

- **严格按阶段开发，不一次生成全部代码**。
- 每阶段完成后：输出代码 → 解释设计 → 给出运行方式 → 等待用户确认后进入下一阶段。
- 企业级分层：Controller-Service-Repository。
- 方法不超过 50 行；核心代码加注释；不写 Demo 垃圾代码。
- 保证后续可扩展 Agent 能力。

---

## 5. 关键设计约定（务必遵守）

### 包结构

- **包基址**：`com.aifp.aiagent`（aifp = AI File Parser；aiagent 为模块名）。
- 分层包：`controller` / `service`(+`service.impl`) / `repository`(
  数据层，Mapper) / `entity` / `dto` / `config` / `common` / `exception` / `ai` / `document` / `parser` / `vector`。
- 启动类 `@MapperScan("com.aifp.aiagent.repository")`。

### 统一返回

- `Result<T>`（`com.aifp.aiagent.common.Result`）：字段 `code`/`message`/`data`/`timestamp`。
- 静态工厂：`Result.success()` / `Result.success(data)` / `Result.fail(ResultCode.X)` / `Result.fail(code, msg)`。
- 状态码枚举 `ResultCode`：分段编码。
    - 2xx 成功；4xx 客户端错误；5xx 服务端错误
    - 2xxx 文件解析；3xxx AI 调用；4xxx 向量检索；5xxx 动态表单
- 新增模块错误码需在 `ResultCode` 追加对应段。

### 异常处理

- 业务异常 `BusinessException(ResultCode.X, "msg")`，由 `GlobalExceptionHandler`(`@RestControllerAdvice`)
  捕获转 `Result.fail`。
- `@Valid` 校验失败 → `MethodArgumentNotValidException` → `code=40001(PARAM_VALID_ERROR)`。
- 不要在 Controller 手写 try-catch，统一抛 `BusinessException`。

### MyBatis-Plus 约定

- 主键：`@TableId(type = IdType.ASSIGN_ID)` 雪花算法（Long）。
- 逻辑删除：全局 `logic-delete-field=deleted`，0 正常 1 删除，实体字段标 `@TableLogic`。**所有表都应有 deleted 列**。
- 审计字段：`create_time`/`update_time`，实体用 `@TableField(fill=INSERT)`/`(fill=INSERT_UPDATE)`
  ，由 `MybatisMetaObjectHandler` 自动填充。
- 实体基类 `BaseEntity`（含 id/createTime/updateTime/deleted），业务实体继承它。
- 表名下划线、字段驼峰自动映射（`table-underline=true`、`map-underscore-to-camel-case=true`）。
- 枚举持久化用 `@EnumValue` 标在 code 字段上。
- 分页拦截器已配（`MybatisPlusConfig`，需 `mybatis-plus-jsqlparser` 依赖，**3.5.6+ 已拆分**）。
- 不写 Mapper XML，CRUD 用 `BaseMapper` + `LambdaQueryWrapper`。

### AI / Qwen 配置

- 配置前缀：`spring.ai.dashscope`，模型 `qwen-plus`，`temperature: 0.3`（字段抽取偏稳定）。
- API Key 通过环境变量 `AI_DASHSCOPE_API_KEY` 注入（**启动必需，否则 DashScopeAgentAutoConfiguration 抛异常导致启动失败
  **）。

### 配置安全

- 所有敏感项用 `${ENV:默认值}` 占位符；DB/Qwen Key 走环境变量。
- context-path = `/aifp`，端口 8080。完整接口路径形如 `/aifp/form/...`。

### 日志

- `logback-spring.xml`：dev 控制台彩色输出；prod 按天滚动+压缩保留 30 天，错误日志单独归档。
- `%wEx` 转换词需手动注册 `ExtendedWhitespaceThrowableProxyConverter`（已修复，勿删）。

---

## 6. 已完成内容详情

### 阶段 1：项目骨架

- `pom.xml`：Spring Boot 3.5.13 parent + Java 21 + Spring AI BOM + Spring AI Alibaba + MyBatis-Plus + MySQL + Lombok +
  Validation + Actuator。
- `AiFileParserApplication`：启动类（`@MapperScan` + `@EnableTransactionManagement`）。
- `common/Result.java` + `common/ResultCode.java`：统一返回与状态码。
- `exception/BusinessException.java` + `exception/GlobalExceptionHandler.java`：全局异常。
- `config/MybatisPlusConfig.java`：分页+乐观锁拦截器。
- `config/MybatisMetaObjectHandler.java`：审计字段自动填充。
- `controller/HealthController.java`：`GET /health/ping` 骨架自检。
- `resources/application.yml` + `resources/logback-spring.xml`。
- `.gitignore`。

#### 已修复问题

- **Logback `%wEx` 报错**：自定义 `logback-spring.xml` 未引入 Spring Boot
  defaults.xml，需手动注册 `<conversionRule conversionWord="wEx" converterClass="org.springframework.boot.logging.logback.ExtendedWhitespaceThrowableProxyConverter"/>`。

### 阶段 2：动态表单管理模块

#### 数据库表（`src/main/resources/db/schema.sql`，手动执行）

- `form_definition`：id / form_name / description / create_time / update_time / deleted。
- `form_field_definition`：id / form_id / field_name / field_code / field_type / required / description / sort /
  create_time / update_time / deleted。
    - 唯一索引 `uk_form_field_code(form_id, field_code)`。

#### FieldType 枚举（`entity/enums/FieldType.java`）

STRING/INTEGER/DECIMAL/DATE/BOOLEAN，每项带 `code`(`@EnumValue`) + `label` + **`jsonSchemaType`**（为阶段 6 AI Prompt
生成预留：string/integer/number/string/boolean）。

#### 实体

- `entity/BaseEntity.java`（抽象基类）。
- `entity/FormDefinition.java`、`entity/FormFieldDefinition.java`（继承 BaseEntity）。

#### Mapper（`repository` 包）

- `FormDefinitionMapper`、`FormFieldDefinitionMapper`（`extends BaseMapper`）。

#### DTO（`dto` 包）

- `FormCreateDTO`：formName(@NotBlank) + description + fields(List<FormFieldCreateDTO>, @Valid, 可选)。
- `FormFieldCreateDTO`：fieldName / fieldCode(@Pattern `^[a-zA-Z][a-zA-Z0-9_]*$`) / fieldType(@NotNull) / required /
  description / sort。
- `FormVO` / `FormFieldVO`：展示 VO。

#### Service

- `service/FormService.java` + `service/impl/FormServiceImpl.java`。
- 方法：`createForm`(事务,表单头+可选字段批插) / `getFormById` / `addField` / `deleteField`(软删)。
- 字段批插用循环 insert（事务内，量小）；field_code 批内+DB 双重唯一校验。

#### Controller（`controller/FormController.java`，前缀 `/form`）

| 方法     | 路径                           | 说明                   |
|--------|------------------------------|----------------------|
| POST   | `/form/create`               | 创建表单（表单头+可选字段列表，同事务） |
| GET    | `/form/{id}`                 | 查询表单详情（字段按 sort 升序）  |
| POST   | `/form/{id}/field`           | 追加单个字段               |
| DELETE | `/form/{id}/field/{fieldId}` | 删除字段（软删）             |

### 测试（`src/test/java/com/aifp/aiagent/controller/FormControllerTest.java`）

- **standalone MockMvc**（`MockMvcBuilders.standaloneSetup` + `@ExtendWith(MockitoExtension.class)`）。
    - 不用 `@WebMvcTest`：因 `@MapperScan` 在启动类上会触发 mapper 装配，需 `sqlSessionFactory`，切片测试无法离线启动。
- `@Mock FormService` + `@InjectMocks FormController`，`@BeforeEach` 装配 MockMvc + `GlobalExceptionHandler` + Jackson
  转换器。
- ObjectMapper 注册 `JavaTimeModule`（`FormVO` 含 `LocalDateTime`，否则序列化报 500）。
- void 方法用 `doThrow().when(...)`，不能用 `when(...).thenThrow()`。
- 共 10 个用例全绿，覆盖 4 接口正向 + 校验失败(40001) + BusinessException 转 Result(5001/5002/5003)。

#### 测试运行

```powershell
mvn -Dtest=FormControllerTest test
```

---

## 7. 当前完整文件结构

```
aiFileParser/
├── pom.xml
├── .gitignore
├── .trae/documents/                      # 规划文档(Plan)
│   ├── phase2-form-module.md
│   ├── fix-logback-wex-converter.md
│   └── phase-test-formcontroller.md
└── src/
    ├── main/
    │   ├── java/com/aifp/aiagent/
    │   │   ├── AiFileParserApplication.java
    │   │   ├── common/{Result.java, ResultCode.java}
    │   │   ├── config/{MybatisPlusConfig.java, MybatisMetaObjectHandler.java}
    │   │   ├── controller/{FormController.java, HealthController.java}
    │   │   ├── dto/{FormCreateDTO, FormFieldCreateDTO, FormVO, FormFieldVO}.java
    │   │   ├── entity/{BaseEntity, FormDefinition, FormFieldDefinition}.java
    │   │   │   └── enums/FieldType.java
    │   │   ├── exception/{BusinessException, GlobalExceptionHandler}.java
    │   │   ├── repository/{FormDefinitionMapper, FormFieldDefinitionMapper}.java
    │   │   └── service/{FormService.java, impl/FormServiceImpl.java}
    │   │       # ai/ document/ parser/ vector/ 包待后续阶段填充
    │   └── resources/
    │       ├── application.yml
    │       ├── logback-spring.xml
    │       ├── db/schema.sql
    │       └── doc/PROJECT_CONTEXT.md      # 本文档
    └── test/java/com/aifp/aiagent/controller/FormControllerTest.java
```

---

## 8. 运行方式

### 前置

1. 本地 MySQL 8.0.46，建库 `aifileparser`。
2. 执行 `src/main/resources/db/schema.sql` 建表。
3. 阿里云百炼平台获取 DashScope API Key。

### 启动

```powershell
$env:AI_DASHSCOPE_API_KEY="你的Key"
$env:DB_PASSWORD="你的MySQL密码"   # 若非默认 root/root
mvn spring-boot:run
```

### 验证

```powershell
curl http://localhost:8080/aifp/health/ping
```

### ⚠️ 已知阻塞

- **启动必需 `AI_DASHSCOPE_API_KEY`**，否则 `DashScopeAgentAutoConfiguration` 抛异常。若仅做非 AI 模块开发，需排除该自动配置（暂未做，阶段
  6 接入 AI 时统一处理）。

---

## 9. 给新对话 AI 的工作指引

1. **先读本文件 + `.trae/documents/` 下的阶段规划文档**，再动代码。
2. **严格遵守上述"关键设计约定"**，尤其是包基址、统一返回、异常处理、MyBatis-Plus 约定。
3. **继续按阶段开发**：当前下一步是阶段 3（文件上传与存储服务）。
4. **修改前先读相关源码**，不要凭假设改代码。
5. **每个阶段完成后等待用户确认**，不要一次性推进多个阶段。
6. **遇到启动/编译错误先查 surefire-reports 与实际报错根因**，不要盲目重试。
7. 计划模式(`/plan`)下：必须先 Explore 代码再写计划，计划写入 `.trae/documents/`，用 `NotifyUser` 提交审批。
