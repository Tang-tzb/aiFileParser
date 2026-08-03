# 阶段 4：文件解析模块

## 摘要

实现统一文件解析层：`FileParser` 接口 + PDF/Excel/Word 三个解析器 + OCR 预留接口 + `Document`
统一模型 + `FileParserRegistry` 策略自动选择。**纯解析模块**：不接入
FileService、不加端点、不做状态流转编排（用户已确认，编排留给后续集成阶段）。用单元测试验证（生成真实小文件解析）。

## 当前状态分析（基于源码核对）

- 包基址 `com.aifp.aiagent`；已预留 `parser`、`document`、`ai`、`vector` 包。
- `FileType` 枚举（`entity/enums/FileType.java`）现有：PDF/EXCEL/WORD/**TXT**/OTHER。OCR 无对应 FileType（OCR
  是处理扫描件/图片的能力，非文件类型）。
- `FileStorageService.load(relativePath)` 可把存储相对路径解析为 `Resource`（阶段 7 编排时用，本阶段不用）。
- `pom.xml` **尚未引入 PDFBox / POI** → 本阶段新增。Spring Boot 3.5.13 BOM 托管 `org.apache.pdfbox:pdfbox`
  与 `org.apache.poi:poi-ooxml` 版本，故无需写 `<version>`。
- `ResultCode` 既有：`FILE_PARSE_ERROR(2001)`、`FILE_TYPE_NOT_SUPPORT(2002)`，复用，不新增。
- `BusinessException(ResultCode.X, msg)` + `GlobalExceptionHandler` 链路可复用。
- ⚠️ Spring AI 依赖中存在 `org.springframework.ai.document.Document` 类，后续阶段 5/6 会用到；本项目的 `Document`
  放在 `com.aifp.aiagent.document` 独立包，按需用全限定名避免冲突。

## 关键设计决策

1. **纯解析模块**（用户已确认）：不修改 FileService、不新增 Controller 端点、不编排状态流转。仅交付解析器 + 模型 +
   策略注册表，单元测试验证。
2. **策略模式**：`FileParser` 接口含 `Document parse(java.io.File)` + `FileType supportedType()`；`FileParserRegistry`
   注入所有 `FileParser` bean，按 `supportedType()` 构建 `Map<FileType, FileParser>`，`get(FileType)`
   自动选择；未注册类型抛 `FILE_TYPE_NOT_SUPPORT`。
3. **OCR 预留为独立接口**（非 FileParser 实现）：OCR 不是文件类型而是能力（处理扫描件/图片），不参与 FileType
   策略选择。`OcrParser` 接口 + `PaddleOcrParser` 桩实现（调用抛 `UnsupportedOperationException`，作为 PaddleOCR 接入点）。本阶段不把
   OCR 接入 PDFParser（避免解析任何扫描 PDF 都抛异常）。
4. **Document 模型
   **：`Document{content:String, metadata:DocumentMetadata}`；`DocumentMetadata{fileName:String, page:Integer, type:FileType}`。
    - `page` 语义按类型差异化（文档化）：PDF=页数、Excel=工作表数、Word=段落数。
5. **PDFParser (PDFBox)**：`PDDocument.load(file)` + `PDFTextStripper` 抽取全文，`page=文档页数`，try-with-resources 关闭。
6. **ExcelParser (POI)**：`WorkbookFactory.create(file)`（自动识别 xls/xlsx），`DataFormatter` 取单元格文本，按"工作表→行"
   拼装，`page=工作表数`。
7. **WordParser (POI XWPF)**：仅支持 `.docx`（`XWPFDocument`），遍历段落取文本，`page=段落数`；`.doc`
   旧版格式抛 `FILE_PARSE_ERROR("暂不支持.doc，请转.docx")`。
8. **TXT 不在本阶段范围**：FileType 虽含 TXT，但用户阶段 4 仅列 PDF/Excel/Word/OCR，故不实现 TxtParser；registry 对 TXT
   抛 `FILE_TYPE_NOT_SUPPORT`（文档化此缺口，后续按需补）。
9. **不写 Mapper/DB**：解析模块无持久化。
10. **解析异常**：IO/解析异常统一 catch 转 `BusinessException(FILE_PARSE_ERROR, msg)`，不泄漏堆栈。

## 数据库设计

无（纯解析模块，无持久化）。

## 待新增/修改文件清单

### 依赖

- `pom.xml`（编辑）：新增
  ```xml
  <dependency>
      <groupId>org.apache.pdfbox</groupId>
      <artifactId>pdfbox</artifactId>
  </dependency>
  <dependency>
      <groupId>org.apache.poi</groupId>
      <artifactId>poi-ooxml</artifactId>
  </dependency>
  ```
  （版本由 Spring Boot BOM 托管，不写 version）

### 统一模型（`document` 包）

- `document/Document.java`：`@Data`，字段 `content`(String) + `metadata`(DocumentMetadata)。
- `document/DocumentMetadata.java`：`@Data` + `@Builder`，字段 `fileName`(String) / `page`(Integer) / `type`(FileType)。

### 解析器（`parser` 包）

- `parser/FileParser.java`（接口）：
  ```java
  public interface FileParser {
      /** 支持的文件类型（用于策略注册） */
      FileType supportedType();
      /** 解析文件为统一 Document 模型 */
      Document parse(File file);
  }
  ```
- `parser/PdfParser.java`（`@Component`，supportedType=PDF）：PDFBox 实现。
- `parser/ExcelParser.java`（`@Component`，supportedType=EXCEL）：POI WorkbookFactory 实现。
- `parser/WordParser.java`（`@Component`，supportedType=WORD）：POI XWPF 实现，.doc 抛错。
- `parser/FileParserRegistry.java`（`@Component`）：注入 `List<FileParser>`
  ，构建 `Map<FileType, FileParser>`，`get(FileType)` 选择。
  ```java
  public FileParser get(FileType type) {
      FileParser p = parsers.get(type);
      if (p == null) throw new BusinessException(ResultCode.FILE_TYPE_NOT_SUPPORT,
              "无可用解析器: " + type);
      return p;
  }
  ```

### OCR 预留（`parser/ocr` 包）

- `parser/ocr/OcrParser.java`（接口）：`String recognize(File file)` —— OCR 识别契约。
- `parser/ocr/PaddleOcrParser.java`（`@Component` 桩实现）：`recognize`
  抛 `UnsupportedOperationException("OCR 未实现，预留 PaddleOCR 接入点")`。

### 测试（`src/test/java/com/aifp/aiagent/parser`）

> 用同套 PDFBox/POI 在 `@TempDir` 生成真实小文件做解析验证。

- `parser/FileParserRegistryTest.java`：
    - `getPdf_returnsPdfParser` / `getExcel` / `getWord`。
    - `getUnsupportedType_throws2002`（TXT/OTHER → FILE_TYPE_NOT_SUPPORT）。
- `parser/PdfParserTest.java`：用 PDFBox 写一页含文本的 PDF → 解析 → 断言 content 含文本、metadata.type=PDF、page=1。
- `parser/ExcelParserTest.java`：用 POI 写 1 sheet 2 行 → 解析 → 断言 content 含单元格值、type=EXCEL、page=1。
- `parser/WordParserTest.java`：用 POI XWPF 写 2 段 → 解析 → 断言 content 含段落文本、type=WORD、page=2。
- `parser/ocr/PaddleOcrParserTest.java`：断言 `recognize` 抛 `UnsupportedOperationException`。

## 验证步骤

1. **编译**：`mvn clean compile -DskipTests`（0 error；若 BOM 未托管 pdfbox/poi 版本则补 version）。
2. **单元测试**：`mvn -Dtest="com.aifp.aiagent.parser.*" test`
   预期：registry 选择 + 3 个解析器端到端 + OCR 桩 全绿。
3. 关键检查点：
    - `FileParserRegistry.get(PDF)` 返回 `PdfParser` 实例；`get(TXT)` 抛 2002。
    - PDFParser 能从真实 PDF 抽到文本，page=页数。
    - ExcelParser 抽到单元格文本，page=工作表数。
    - WordParser 抽到段落文本，page=段落数；.doc 抛 FILE_PARSE_ERROR。
    - `PaddleOcrParser.recognize` 抛 `UnsupportedOperationException`（预留行为）。
4. 不启动应用（纯模块，无端点）；无需 MySQL/DashScope Key。

## 假设与决策汇总

- 纯解析模块，不接入 FileService/不加端点/不编排状态（用户已确认）。
- 策略模式：`FileParser.supportedType()` + `FileParserRegistry` Map 选择。
- OCR 为独立预留接口（`OcrParser` + `PaddleOcrParser` 桩），不接入 PDFParser，不参与 FileType 策略。
- `Document` 放 `com.aifp.aiagent.document`（注意后续 Spring AI Document 全限定名区分）。
- `metadata.page`：PDF=页数 / Excel=工作表数 / Word=段落数（文档化）。
- WordParser 仅 .docx（XWPF）；.doc 抛 FILE_PARSE_ERROR。
- TXT 不实现（本阶段范围外），registry 对 TXT 抛错。
- 复用 `FILE_PARSE_ERROR(2001)` / `FILE_TYPE_NOT_SUPPORT(2002)`，不新增错误码。
- pdfbox / poi-ooxml 版本由 Spring Boot BOM 托管。
- 完成后即停，等待下一阶段（RAG 知识库）。
