# 阶段 2：动态表单管理模块

## 摘要

实现用户自定义表单（表单头 + 字段）的增删查能力，字段设计为后续 AI Prompt / JSON Schema
生成打好基础。覆盖：DDL、Entity、Mapper、Service、Controller、DTO，并复用阶段 1 的 `Result<T>` / `BusinessException` / 全局异常链路。

## 当前状态分析（基于阶段 1 源码核对）

- 包基址 `com.aifp.aiagent`；启动类 `@MapperScan("com.aifp.aiagent.repository")` → 数据层放 `repository` 包。
- MyBatis-Plus 全局配置（`application.yml`）：`id-type=assign_id`(Long 雪花)、`logic-delete-field=deleted`(
  1删0留)、`table-underline=true`、`map-underscore-to-camel-case=true`、`type-aliases-package=com.aifp.aiagent.entity`。
- 统一返回 `Result.success(data)` / `Result.fail(ResultCode.X,"msg")`
  ；业务异常 `throw new BusinessException(ResultCode.X,"msg")`。
- `@EnableTransactionManagement` 已在启动类 → 可直接用 `@Transactional`。
- `ResultCode` 现有段：2xxx 文件 / 3xxx AI / 4xxx 向量 → 表单模块用 **5xxx**。
- context-path=`/aifp`，故完整路径为 `/aifp/form/...`。
- 前置阻塞（来自阶段 1）：启动需 `AI_DASHSCOPE_API_KEY` 环境变量，否则 DashScope 自动配置抛异常导致启动失败。本阶段不改动 AI
  配置，验证时通过设置该环境变量启动（与阶段 1 一致）。

## 关键设计决策

1. **创建表单语义**（已与用户确认）：`POST /form/create` 接收表单头 + **可选**
   字段列表，同事务批量插入；仍保留 `POST /form/{id}/field` 用于后续追加字段。
2. **审计与逻辑删除列**：两张表均加 `create_time` / `update_time` / `deleted`
   。理由：全局已声明 `logic-delete-field=deleted`
   （不加该列则逻辑删除失效），且表单/字段均需软删除与审计。`form_field_definition` 用户未显式列这三列，但为保持企业一致性与全局配置可用性，统一补齐。
3. **field_type 枚举持久化**：用 MyBatis-Plus `@EnumValue` 存枚举 `code` 字符串（"STRING"/"INTEGER"/...），避免 ordinal 隐患。
4. **field_code 唯一性**：同一表单内 `field_code` 唯一，DB 加 `UNIQUE(form_id, field_code)`，Service 层再做一次校验给出友好提示。
5. **自动填充**：新增 `MetaObjectHandler` 自动填 `create_time`/`update_time`，实体用 `@TableField(fill=...)`，无需手动 set。
6. **AI Prompt 友好**：`FieldType` 枚举携带 `jsonSchemaType`
   （string/integer/number/string/boolean），字段实体含 `field_code` + `field_type` + `description`，后续阶段可直接据此生成
   JSON Schema 与抽取 Prompt。
7. **批插实现**：字段批插用循环 `mapper.insert()`（在 `@Transactional` 内，字段数典型 5~20，足够；避免引入 `IService`/`Db`
   工具的额外抽象）。
8. **不写 Mapper XML**：CRUD 与条件查询用 `BaseMapper` + `LambdaQueryWrapper` 即可，`application.yml`
   的 `mapper-locations` 已预留。
9. **不建数据库外键**：仅加索引（`idx_form_id`），保持 MP 项目常规灵活性。
10. **删除字段**：软删除（`@TableLogic` + `deleteById`），与全局逻辑删除一致。
11. **add-field 入参**：单个字段（贴合"添加字段"字面语义）；批量场景由 create-form 的可选字段列表覆盖。

## 数据库设计（DDL）

文件：`src/main/resources/db/schema.sql`（手动执行；不接入 `spring.sql.init` 以防生产误操作）

```sql
CREATE TABLE IF NOT EXISTS form_definition (
  id          BIGINT       NOT NULL                  COMMENT '主键ID(雪花算法)',
  form_name   VARCHAR(100) NOT NULL                  COMMENT '表单名称',
  description VARCHAR(500) DEFAULT NULL               COMMENT '表单描述',
  create_time DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP                                COMMENT '创建时间',
  update_time DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP    COMMENT '更新时间',
  deleted     TINYINT      NOT NULL DEFAULT 0         COMMENT '逻辑删除:0正常 1删除',
  PRIMARY KEY (id),
  KEY idx_form_name (form_name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='表单定义表';

CREATE TABLE IF NOT EXISTS form_field_definition (
  id          BIGINT      NOT NULL                    COMMENT '主键ID(雪花算法)',
  form_id     BIGINT      NOT NULL                    COMMENT '所属表单ID',
  field_name  VARCHAR(100) NOT NULL                   COMMENT '字段名称',
  field_code  VARCHAR(64) NOT NULL                     COMMENT '字段编码',
  field_type  VARCHAR(20) NOT NULL                     COMMENT '字段类型:STRING/INTEGER/DECIMAL/DATE/BOOLEAN',
  required    TINYINT(1)  NOT NULL DEFAULT 0           COMMENT '是否必填:0否1是',
  description VARCHAR(500) DEFAULT NULL               COMMENT '字段描述',
  sort        INT         NOT NULL DEFAULT 0           COMMENT '排序号',
  create_time DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP                                COMMENT '创建时间',
  update_time DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP    COMMENT '更新时间',
  deleted     TINYINT     NOT NULL DEFAULT 0           COMMENT '逻辑删除:0正常 1删除',
  PRIMARY KEY (id),
  UNIQUE KEY uk_form_field_code (form_id, field_code),
  KEY idx_form_id (form_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='表单字段定义表';
```

## 待新增/修改文件清单

### 配置

- `src/main/java/com/aifp/aiagent/config/MybatisMetaObjectHandler.java`（新增）：实现 `MetaObjectHandler`，insert
  填 `createTime`/`updateTime`，update 填 `updateTime`。

### 实体层（`entity` 包）

- `entity/BaseEntity.java`
  （新增）：抽象基类，含 `id`(`@TableId(ASSIGN_ID)`)、`createTime`(`fill=INSERT`)、`updateTime`(`fill=INSERT_UPDATE`)、`deleted`(`@TableLogic`)。
- `entity/FormDefinition.java`（新增）：`@TableName("form_definition")`，字段 `formName`、`description`，继承 `BaseEntity`。
- `entity/FormFieldDefinition.java`（新增）：`@TableName("form_field_definition")`
  ，字段 `formId`、`fieldName`、`fieldCode`、`fieldType`(枚举)、`required`(Boolean)、`description`、`sort`，继承 `BaseEntity`。
- `entity/enums/FieldType.java`（新增）：枚举
  STRING/INTEGER/DECIMAL/DATE/BOOLEAN，每项带 `code`(`@EnumValue`)、`label`、`jsonSchemaType`。

```java
@Getter @AllArgsConstructor
public enum FieldType {
    STRING ("STRING",  "字符串", "string"),
    INTEGER("INTEGER","整数",   "integer"),
    DECIMAL("DECIMAL","小数",   "number"),
    DATE   ("DATE",   "日期",   "string"),
    BOOLEAN("BOOLEAN","布尔",   "boolean");
    @EnumValue private final String code;
    private final String label;
    private final String jsonSchemaType;
}
```

### 数据访问层（`repository` 包，与 `@MapperScan` 一致）

- `repository/FormDefinitionMapper.java`：`extends BaseMapper<FormDefinition>`。
- `repository/FormFieldDefinitionMapper.java`：`extends BaseMapper<FormFieldDefinition>`。

### DTO（`dto` 包）

- `dto/FormCreateDTO.java`：`formName`(`@NotBlank`)、`description`、`fields`(`List<FormFieldCreateDTO>`，`@Valid`，可空)。
- `dto/FormFieldCreateDTO.java`：`fieldName`(`@NotBlank`)、`fieldCode`(`@NotBlank` + `@Pattern("^[a-zA-Z][a-zA-Z0-9_]*$")`)、`fieldType`(`@NotNull`)、`required`、`description`、`sort`。
- `dto/FormVO.java`：`formId`、`formName`、`description`、`createTime`、`updateTime`、`fields`(`List<FormFieldVO>`)。
- `dto/FormFieldVO.java`：`fieldId`、`fieldName`、`fieldCode`、`fieldType`、`required`、`description`、`sort`。

### Service（`service` / `service.impl` 包）

- `service/FormService.java`（接口）：
    - `Long createForm(FormCreateDTO dto)`
    - `FormVO getFormById(Long id)`
    - `Long addField(Long formId, FormFieldCreateDTO dto)`
    - `void deleteField(Long formId, Long fieldId)`
- `service/impl/FormServiceImpl.java`：
    - 注入两个 Mapper；写方法标 `@Transactional`。
    - `createForm`：插表单头 → 若 fields 非空：先校验批内 field_code 不重复 → 循环插字段 → 返回 formId。
    - `getFormById`：`selectById`，空则 `BusinessException(FORM_NOT_FOUND)`；查字段 `orderByAsc(sort)`；组装 `FormVO`。
    - `addField`：校验表单存在 → 校验 field_code 不重复(`FIELD_CODE_DUPLICATE`) → 插入 → 返回 fieldId。
    - `deleteField`：校验字段存在且 `formId` 匹配(`FIELD_NOT_FOUND`) → `deleteById`(软删)。

### Controller（`controller` 包）

- `controller/FormController.java`（`@RestController @RequestMapping("/form")`）：
    - `POST /create` → `Result<Long>`
    - `GET /{id}` → `Result<FormVO>`
    - `POST /{id}/field` → `Result<Long>`
    - `DELETE /{id}/field/{fieldId}` → `Result<Void>`

### 修改

- `common/ResultCode.java`（编辑）：在 4xxx 之后追加 5xxx 段：
    - `FORM_NOT_FOUND(5001,"表单不存在")`
    - `FIELD_CODE_DUPLICATE(5002,"字段编码在表单内重复")`
    - `FIELD_NOT_FOUND(5003,"字段不存在")`

## 接口示例（context-path=/aifp）

```http
POST /aifp/form/create
{
  "formName": "项目申报表",
  "description": "用于项目投资申报",
  "fields": [
    {"fieldName":"项目名称","fieldCode":"projectName","fieldType":"STRING","required":true,"sort":1},
    {"fieldName":"投资金额","fieldCode":"investAmount","fieldType":"DECIMAL","required":true,"sort":2},
    {"fieldName":"建设年份","fieldCode":"buildYear","fieldType":"INTEGER","required":false,"sort":3},
    {"fieldName":"负责人","  fieldCode":"owner","fieldType":"STRING","required":true,"sort":4}
  ]
}
→ {"code":200,"message":"操作成功","data":1785508135,...}

GET /aifp/form/{id}
→ {"code":200,...,"data":{"formId":...,"formName":"项目申报表","fields":[...]}}

POST /aifp/form/{id}/field
{"fieldName":"备注","fieldCode":"remark","fieldType":"STRING","required":false,"sort":5}
→ {"code":200,"data":<fieldId>}

DELETE /aifp/form/{id}/field/{fieldId}
→ {"code":200,"data":null}
```

## 验证步骤

1. **建库建表**：在 MySQL `aifileparser` 库执行 `src/main/resources/db/schema.sql`。
2. **编译**：`mvn clean compile -DskipTests`（应 0 error）。
3. **启动**：`$env:AI_DASHSCOPE_API_KEY="你的Key"; mvn spring-boot:run`（需 Key 才能越过 DashScope 自动装配，与阶段 1
   一致；本阶段功能不依赖 AI）。
4. **接口联调**（PowerShell + curl 或 Postman）：
    - `POST /aifp/form/create`（带 4 字段）→ 返回 formId。
    - `GET /aifp/form/{formId}` → 校验字段齐全且按 sort 排序。
    - `POST /aifp/form/{formId}/field`（加 remark）→ 再 GET 确认含新字段。
    - 重复 field_code 调 `addField` → 返回 5002 `FIELD_CODE_DUPLICATE`。
    - `DELETE /aifp/form/{formId}/field/{fieldId}` → 再 GET 确认字段已移除（软删）。
    - `GET /aifp/form/不存在id` → 返回 5001 `FORM_NOT_FOUND`。
5. 校验 `create_time`/`update_time` 由 `MetaObjectHandler` 自动写入、`deleted` 软删为 1。

## 假设与决策汇总

- 表单创建支持可选字段批插（用户已确认）。
- 两表均加 `deleted`+`create_time`+`update_time`（企业一致性 + 全局逻辑删除所需）。
- `field_type` 用 `@EnumValue` 存字符串 code。
- `field_code` 表内唯一（DB 唯一索引 + Service 校验）。
- 字段批插用循环 insert（事务内，量小）。
- 删除为软删（`@TableLogic`）。
- 不引入 Mapper XML / IService / Db 工具，避免过度抽象。
- 本阶段不触碰 AI 配置；启动仍需 `AI_DASHSCOPE_API_KEY`（阶段 1 既定）。
- 完成后即停，等待下一阶段（文件解析层）。
