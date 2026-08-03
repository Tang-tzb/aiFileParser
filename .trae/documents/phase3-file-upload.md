# 阶段 3：文件上传与存储服务

## 摘要

实现 PDF/Excel/Word 文件上传：保存到本地磁盘、判断文件类型、写入 `file_record` 记录、状态管理（初始 `UPLOADED`
，并提供状态流转方法供后续阶段调用）。**不与 AI 解析耦合**。存储层抽象为 `FileStorageService` 接口 + 本地实现，便于后续替换
OSS。

## 当前状态分析（基于源码核对）

- 包基址 `com.aifp.aiagent`；启动类 `@MapperScan("com.aifp.aiagent.repository")`。
- `application.yml` 已配 `spring.servlet.multipart`（max 100MB），但**无 `file.upload-dir` 配置** → 需新增。
- `BaseEntity` 提供 `id`(`@TableId ASSIGN_ID`)/`createTime`/`updateTime`/`deleted`(`@TableLogic`)
  ，审计字段由 `MybatisMetaObjectHandler` 自动填充。
- 枚举持久化模式：`@EnumValue` 标在 `code` 字段（参考 `FieldType`）。
- `ResultCode` 已有文件段，**无需新增**：`FILE_TYPE_NOT_SUPPORT(2002)`、`FILE_UPLOAD_ERROR(2003)`、`FILE_NOT_FOUND(2004)`。
- DDL 模式：表含 `create_time`/`update_time`/`deleted` 审计列（参考 `schema.sql` 的 `form_definition`）。
- context-path=`/aifp`，完整路径 `/aifp/file/upload`。
- 前置阻塞：启动需 `AI_DASHSCOPE_API_KEY`（阶段 1 既定，本阶段不触碰 AI 配置）。

## 关键设计决策

1. **存储抽象**（已与用户确认）：`FileStorageService` 接口 + `LocalFileStorageServiceImpl`，本地磁盘实现；后续加 OSS
   只需新增实现类，不改 `FileService`。
2. **file_record 继承 BaseEntity**：用户显式列出 `id/file_name/file_type/file_path/status/create_time`，但按阶段 2
   既有约定（审计列 + 逻辑删除列），统一让实体继承 `BaseEntity` 获得 `update_time`/`deleted`。`update_time`
   用于状态流转审计（UPLOADED→PARSING→...）。
3. **FileType 枚举**：`PDF`/`EXCEL`/`WORD`/`OTHER`，`@EnumValue` 存 code。`OTHER` 用于兜底（实际接口会拒绝未支持类型，但枚举完整）。
4. **FileStatus 枚举**：`UPLOADED`/`PARSING`/`VECTORING`/`EXTRACTING`/`SUCCESS`/`FAILED`，`@EnumValue` 存
   code。本阶段仅使用 `UPLOADED`，其余供后续阶段调用 `updateStatus` 流转。
5. **文件类型判断**：扩展名（白名单 pdf/xlsx/xls/docx/doc）+ `MultipartFile.getContentType()` 双重校验；扩展名映射到
   FileType。不做 magic bytes 解析（避免过度工程，阶段 4 可按需增强）。
6. **存储文件名**：UUID + 原扩展名（如 `a1b2...c3.pdf`），避免冲突与中文路径问题；原始文件名存 `file_name`
   列，存储相对路径存 `file_path` 列（相对 `upload-dir`，如 `2026/07/a1b2...c3.pdf`）。
7. **目录按年月分**：`{upload-dir}/yyyy/MM/{uuid}.ext`，避免单目录文件过多。
8. **upload-dir 配置**：`application.yml` 新增 `file.upload-dir: ${FILE_UPLOAD_DIR:./uploads}`
   ；启动时确保目录存在（`@PostConstruct` 或首次写入时创建）。
9. **状态管理**：提供 `updateStatus(Long id, FileStatus status)` 方法（供阶段 4/5/6 调用），仅更新 status
   字段（`mapper.updateById` 带上 status）；本阶段不实现解析逻辑，不与 AI 耦合。
10. **upload 返回 VO**：返回 `FileUploadVO`（含 fileId、fileName、fileType、filePath、status、createTime），比单返回 id 更有用。
11. **不写 Mapper XML**：用 `BaseMapper`。
12. **空文件校验**：`MultipartFile.isEmpty()` → `FILE_UPLOAD_ERROR`。

## 数据库设计（DDL）

追加到 `src/main/resources/db/schema.sql`：

```sql
-- ---------------- 文件记录表 ----------------
CREATE TABLE IF NOT EXISTS file_record (
  id          BIGINT       NOT NULL                  COMMENT '主键ID(雪花算法)',
  file_name   VARCHAR(255) NOT NULL                  COMMENT '原始文件名',
  file_type   VARCHAR(20) NOT NULL                  COMMENT '文件类型:PDF/EXCEL/WORD/OTHER',
  file_path   VARCHAR(500) NOT NULL                  COMMENT '存储相对路径',
  status      VARCHAR(20)  NOT NULL DEFAULT 'UPLOADED' COMMENT '状态:UPLOADED/PARSING/VECTORING/EXTRACTING/SUCCESS/FAILED',
  create_time DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP                                COMMENT '创建时间',
  update_time DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP    COMMENT '更新时间',
  deleted     TINYINT      NOT NULL DEFAULT 0         COMMENT '逻辑删除:0正常 1删除',
  PRIMARY KEY (id),
  KEY idx_file_status (status),
  KEY idx_file_type (file_type)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='文件记录表';
```

## 待新增/修改文件清单

### 配置

- `src/main/resources/application.yml`（编辑）：新增
  ```yaml
  # ---------------- 文件存储 ----------------
  file:
    upload-dir: ${FILE_UPLOAD_DIR:./uploads}
  ```
- `src/main/resources/db/schema.sql`（编辑）：追加 `file_record` 建表语句。
- `.gitignore`（编辑）：追加 `/uploads/`。

### 枚举（`entity/enums` 包）

- `entity/enums/FileType.java`：`PDF`/`EXCEL`/`WORD`/`OTHER`，`@EnumValue code` + `label` + 关联扩展名集合。
- `entity/enums/FileStatus.java`：`UPLOADED`/`PARSING`/`VECTORING`/`EXTRACTING`/`SUCCESS`/`FAILED`，`@EnumValue code` + `label`。

### 实体（`entity` 包）

- `entity/FileRecord.java`：`@TableName("file_record")`，字段 `fileName`/`fileType`(FileType)/`filePath`/`status`(
  FileStatus)，继承 `BaseEntity`。

### 数据访问层（`repository` 包）

- `repository/FileRecordMapper.java`：`extends BaseMapper<FileRecord>`。

### 存储层（`service/storage` 包）

- `service/storage/FileStorageService.java`（接口）：
    - `String store(MultipartFile file, FileType fileType)`：保存文件，返回相对路径。
    - `Resource load(String relativePath)`：按相对路径加载为 Resource（供后续下载/解析用）。
    - `void delete(String relativePath)`：删除文件（后续清理用）。
- `service/storage/LocalFileStorageService.java`（`@Service` 实现）：
    - `@Value("${file.upload-dir}")` 注入目录。
    - `@PostConstruct` 建目录。
    - `store`：年月子目录 + UUID 文件名 + 原扩展名，`Files.copy` 写入，返回相对路径。

### DTO（`dto` 包）

- `dto/FileUploadVO.java`：`fileId`/`fileName`/`fileType`/`filePath`/`status`/`createTime`。
- `dto/FileRecordVO.java`：完整记录展示（含 `updateTime`），供后续查询接口复用。

### Service（`service` / `service.impl` 包）

- `service/FileService.java`（接口）：
    - `FileUploadVO upload(MultipartFile file)`：核心上传。
    - `void updateStatus(Long id, FileStatus status)`：状态流转（供后续阶段）。
    - `FileRecordVO getById(Long id)`：查询记录（供后续阶段/调试）。
- `service/impl/FileServiceImpl.java`（`@Service`，注入 `FileRecordMapper` + `FileStorageService`）：
    - `upload` 流程：空校验 → 类型判断(扩展名+contentType，不支持抛 `FILE_TYPE_NOT_SUPPORT`) → `storageService.store`
      保存 → 构造 `FileRecord`(status=UPLOADED) → `mapper.insert` → 转 VO 返回。保存失败抛 `FILE_UPLOAD_ERROR`。
    - `updateStatus`：`selectById` 空则 `FILE_NOT_FOUND`；设 status → `updateById`。
    - `getById`：`selectById` 空则 `FILE_NOT_FOUND`；转 VO。

### Controller（`controller` 包）

- `controller/FileController.java`（`@RestController @RequestMapping("/file")`）：
    - `POST /upload`：`@RequestParam("file") MultipartFile file` → `Result<FileUploadVO>`。

## 接口示例（context-path=/aifp）

```http
POST /aifp/file/upload
Content-Type: multipart/form-data
file: <PDF/Excel/Word 文件>

→ {
  "code": 200,
  "message": "操作成功",
  "data": {
    "fileId": 1785...,
    "fileName": "项目申报书.pdf",
    "fileType": "PDF",
    "filePath": "2026/07/a1b2c3d4.pdf",
    "status": "UPLOADED",
    "createTime": "2026-07-31T12:00:00"
  }
}
```

错误返回：

- 空文件 / 未传 file → `FILE_UPLOAD_ERROR(2003)`。
- 不支持类型（如 .txt）→ `FILE_TYPE_NOT_SUPPORT(2002)`。
- 超大文件 → Spring multipart 拒绝（500/400，由全局兜底）。

## 验证步骤

1. **建表**：在 MySQL `aifileparser` 库执行追加的 `file_record` DDL。
2. **编译**：`mvn clean compile -DskipTests`（0 error）。
3. **启动**：`$env:AI_DASHSCOPE_API_KEY="你的Key"; mvn spring-boot:run`（需 Key 越过 DashScope 自动装配；本阶段功能不依赖
   AI）。
4. **上传联调**（PowerShell curl）：
   ```powershell
   curl -X POST http://localhost:8080/aifp/file/upload -F "file=@项目申报书.pdf"
   curl -X POST http://localhost:8080/aifp/file/upload -F "file=@data.xlsx"
   curl -X POST http://localhost:8080/aifp/file/upload -F "file=@report.docx"
   ```
   预期：返回 200 + `status=UPLOADED` + 正确 `fileType`。
5. **异常用例**：
    - 传 .txt → `code=2002`。
    - 不传 file → `code=2003`。
6. **磁盘校验**：`./uploads/yyyy/MM/` 下存在 UUID 文件名文件。
7. **DB 校验**：`file_record` 表有一条记录，`status=UPLOADED`，`create_time`/`update_time` 已自动填充。

## 假设与决策汇总

- 存储层抽象为接口+本地实现（用户已确认），后续可加 OSS 实现。
- `file_record` 继承 `BaseEntity`（含 update_time/deleted，与阶段 2 一致）。
- `FileType`/`FileStatus` 用 `@EnumValue` 存字符串 code。
- 文件类型判断用扩展名白名单 + contentType，不做 magic bytes。
- 存储文件名 UUID+扩展名，按年月分子目录。
- 提供 `updateStatus` 供后续阶段流转状态，本阶段不实现解析（不与 AI 耦合）。
- upload 返回 `FileUploadVO`。
- 复用 `ResultCode` 既有文件段，不新增错误码。
- 本阶段不触碰 AI 配置；启动仍需 `AI_DASHSCOPE_API_KEY`。
- 完成后即停，等待下一阶段（文件解析层）。
