# PDF解析改造 阶段0：现状基线与影响分析

> 依据《PDF解析改造方案》阶段 0 要求产出。本阶段**零业务代码修改**，仅阅读源码、梳理依赖、建立现状分析与验证基线。
> 分析基线日期：2026-09-05

---

## 一、当前完整链路图

```
用户上传
  └─ FileController.upload
      └─ FileServiceImpl.upload                （扩展名校验 FileType.ofExtension）
          └─ LocalFileStorageService.store     （{file.upload-dir}/yyyy/MM/{uuid}.{ext}）
              └─ file_record 落库，status = UPLOADED

任务启动
  └─ ParseTaskServiceImpl.start                （生成 taskId，发布初始进度）
      └─ AsyncParseExecutor.run                （@Async("parseExecutor") 独立线程池）

入库编排（0% PARSING → 50% VECTORING → SUCCESS）
  └─ DocumentIngestionServiceImpl.ingest(fileId, ProgressCallback)
      ├─ 幂等检查：FileStatus.SUCCESS 直接跳过
      ├─ updateStatus(PARSING) + callback("PARSING") → 进度 0%
      ├─ FileStorageService.load → java.io.File
      ├─ FileParserRegistry.get(FileType)      （策略：FileType → FileParser）
      │    ├─ PdfParser   （PDFBox）
      │    ├─ ExcelParser （POI）
      │    └─ WordParser  （POI XWPF）
      ├─ ParserDocument（content + metadata），metadata.fileId 由编排层注入
      ├─ updateStatus(VECTORING) + callback("VECTORING") → 进度 50%
      ├─ DocumentChunker.chunk                 （JTokkit / CL100K_BASE，800/200）
      ├─ VectorStoreService.store              （Spring AI VectorStore → Milvus）
      │    └─ Milvus collection: aifp_doc_chunks，1536 维，IVF_FLAT，COSINE
      └─ updateStatus(SUCCESS)

AI 字段抽取（80% EXTRACTING → 100% SUCCESS）
  └─ FieldExtractorServiceImpl.extract(formId, fileId)
      ├─ 幂等 ingest(fileId)
      ├─ FormService 读字段定义（form_definition / form_field_definition）
      ├─ updateStatus(EXTRACTING) → 进度 80%
      ├─ FieldQueryGenerator.generate          （"请从文档中提取{字段}。"）
      ├─ VectorStoreService.search(query, topK=5, filter="fileId == '{fileId}'")
      ├─ 按 Document.id 合并去重
      ├─ ExtractionPromptBuilder               （动态 JSON schema + Retry 反馈）
      ├─ ChatModel（DashScope qwen-plus, temperature=0.3）
      ├─ JSON 解析（剥离 ``` 围栏）→ FieldSchemaValidator.validate
      │    （required/类型/格式校验，万/亿单位、千分位智能转换）
      ├─ 失败按字段错误反馈 Retry（max-attempts=2）
      ├─ updateStatus(SUCCESS)
      └─ ExtractionResult（values + errors + attemptsUsed）

进度推送（贯穿全程）
  └─ ProgressPublisher → Redis（快照 + Pub/Sub）→ ProgressMessageListener → SseEmitterManager
      （TaskProgress：0/50/80/100，FAILED=-1，终态判断 isTerminal）
```

---

## 二、六项验收结论

### 1. PDF 现状解析方式

`PdfParser.parse(File)`：

- PDFBox **3.0.4** `Loader.loadPDF(file)` 一次性加载整个文档；
- `new PDFTextStripper().getText(pd)` 整本抽取，**输出一个 String**；
- 无 Page 级处理、无 `TextPosition`、无坐标、无字体信息、无图片信息；
- metadata 仅 fileName / page(页数) / type / fileId（由编排层注入）。

结论：当前是"全文拍平"模式，任何结构信息在 Parser 层即丢失。

### 2. 扫描 PDF 当前为什么失败

失败链（静默失败，非异常）：

1. 扫描版 PDF 无文字层 → `PDFTextStripper.getText()` 返回**空串**（不报错）；
2. `DocumentChunker.chunk()` 对 blank content 返回**空 List**（`List.of()`）；
3. `VectorStoreService.store(空列表)` → `vectorStore.add([])` 不报错，实际**入库 0 条**；
4. `updateStatus(SUCCESS)` —— **空解析被标记为处理成功**（现有缺陷）；
5. 字段抽取阶段 `search` 命中 0 条 → 抛 `VECTOR_RETRIEVE_ERROR("未检索到相关文档切片")`。

即：扫描件在解析层静默通过，最终在抽取层才暴露失败，且 file_record 状态已为 SUCCESS（幂等门槛），重试会被 `ingest`
的幂等检查直接跳过，无法自愈。该缺陷仅记录，按方案属阶段降级策略（三十一）修复范围。

### 3. 当前 chunk 如何生成

`DocumentChunker.chunk(ParserDocument)`：

- JTokkit `CL100K_BASE` 将全文编码为 token；
- `total <= 800`：单块；
- `total > 800`：滑动窗口，窗口 800、重叠 200、步长 stride=600，块数 = `ceil((total-800)/600) + 1`；
- 解码还原文本 → Spring AI `Document(text, metadata)`。

chunk metadata 仅 6 项：`fileName / fileType / page / fileId(String) / chunkIndex / totalChunks`。
**缺失**：pageStart/pageEnd、titlePath、chunkType、bbox、confidence、sourceType；按 token 硬切，可能截断句子/表格/字段对。

### 4. 当前 ParserDocument 有哪些字段

`ParserDocument`（`document/` 包）：

| 字段       | 类型                     | 说明   |
|----------|------------------------|------|
| content  | String                 | 全文文本 |
| metadata | ParserDocumentMetadata | 元数据  |

`ParserDocumentMetadata`：

| 字段       | 类型       | 说明                                       |
|----------|----------|------------------------------------------|
| fileName | String   | 原始文件名                                    |
| page     | Integer  | PDF=页数 / Excel=工作表数 / Word=段落数           |
| type     | FileType | PDF/EXCEL/WORD/TXT/OTHER                 |
| fileId   | Long     | 由 DocumentIngestionService 注入，供 chunk 过滤 |

结论：模型仅 `content + metadata`，无 ast 字段（阶段 9 兼容方案：扩展 `ast` 字段，content 由 MarkdownRenderer 生成）。

### 5. 哪些代码必须兼容（接口冻结面）

| #  | 兼容项                                                                         | 位置                        | 约束                                    |
|----|-----------------------------------------------------------------------------|---------------------------|---------------------------------------|
| 1  | `FileParser` 接口契约（supportedType/parse→ParserDocument）                       | parser/                   | FileParserRegistry 策略注册依赖             |
| 2  | ExcelParser / WordParser 行为                                                 | parser/                   | 禁止破坏（方案禁止事项 12）                       |
| 3  | `ParserDocument.content/metadata` 旧字段                                       | document/                 | 旧业务继续可用，新增字段而非删除                      |
| 4  | `DocumentIngestionService.ingest` 签名 + SUCCESS 幂等                           | rag/                      | AsyncParseExecutor、FieldExtractor 均调用 |
| 5  | FileStatus 状态机 UPLOADED→PARSING→VECTORING→EXTRACTING→SUCCESS/FAILED         | entity/enums/             | DB status 列 + 幂等依据                    |
| 6  | ProgressCallback("PARSING"/"VECTORING") → 0%/50% 进度                         | rag/ + task/              | SSE 契约（TaskProgress.percent 语义）       |
| 7  | SSE/Redis 进度链路（ProgressPublisher→Listener→SseEmitterManager）                | task/                     | 禁止破坏（方案禁止事项 13）                       |
| 8  | Milvus collection `aifp_doc_chunks`、1536 维、IVF_FLAT/COSINE                  | application.yml           | 禁止更换（方案 28）                           |
| 9  | fileId 过滤表达式 `fileId == '{fileId}'`                                         | FieldExtractorServiceImpl | 禁止破坏（方案禁止事项 14）                       |
| 10 | chunk 现有 metadata key（fileName/fileType/page/fileId/chunkIndex/totalChunks） | DocumentChunker           | Milvus 检索过滤依赖                         |
| 11 | DashScope text-embedding-v2 / qwen-plus 配置                                  | application.yml           | 原则上不修改                                |
| 12 | db schema（file_record/form_definition/form_field_definition）                | resources/db/             | 状态列语义不变                               |
| 13 | 现有 18 个测试类的离线性（JUnit+Mockito，不依赖 MySQL/Milvus）                              | test/                     | 新增测试遵循同样约束                            |

### 6. 哪些代码后续需要修改（影响清单）

| 影响对象                         | 目标形态                                                                               | 关联阶段          |
|------------------------------|------------------------------------------------------------------------------------|---------------|
| PdfParser                    | 重构为 PdfDocumentParser 编排器（Analyzer→Router→PageParser→AST），旧 PdfParser 职责拆解         | 1/2/3/25      |
| 新增 pdf/ 检测与解析层               | PdfAnalyzer、PageAnalyzer、PageProfile、PageParserRouter、4 个 PageParser、坐标/表格/Region  | 1/2/3/5/6/7/8 |
| OcrParser + PaddleOcrParser  | 接口重设计（OcrResult/OcrPage/OcrWord 坐标+置信度），实现替换为 OcrmyPdf/Tesseract（PaddleOCR 保留为扩展点） | 4             |
| ParserDocument               | 扩展 `DocumentAst ast` 字段；content 由 MarkdownRenderer 生成                              | 9/11/27       |
| DocumentChunker              | 升级 HybridSemanticChunker（结构→语义→长度三层），保留 JTokkit 计数能力；RagChunk metadata 扩展          | 12/13         |
| DocumentIngestionServiceImpl | 编排接入 clean（AST 级清洗）环节，其余编排顺序不变                                                     | 10/13         |
| application.yml              | 新增 document.parser.pdf / document.ocr / 阈值配置节                                      | 37            |
| pom.xml                      | Tesseract/OCRmyPDF 调用依赖（tess4j 或本地 CLI 封装）、测试用例                                    | 4/32          |
| DocumentChunker 空内容行为        | 空解析应失败/降级而非静默 SUCCESS（缺陷修复随降级策略落地）                                                 | 31            |

---

## 三、现状缺口表

| #  | 缺口            | 现状证据                                                                                | 后果                            |
|----|---------------|-------------------------------------------------------------------------------------|-------------------------------|
| G1 | 无内容类型检测       | PdfParser 不感知图片/文字分布，全文一个 String                                                    | 扫描件空解析、混合件结构丢失                |
| G2 | OCR 无实现       | PaddleOcrParser.recognize 抛 UnsupportedOperationException；接口仅 `File→String`，无坐标/置信度 | IMAGE_ONLY 页面完全无法处理           |
| G3 | 无坐标系统         | 无 TextPosition/BoundingBox，PDF/图片/OCR 坐标不可比                                         | 无法做 Region 融合与表格还原            |
| G4 | chunk 无结构语义   | 800/200 token 滑动窗口，metadata 6 项                                                     | 表格被切散、标题与正文失联、页码信息只有整文档级 page |
| G5 | 空解析误标 SUCCESS | chunk 空列表 → store([]) → SUCCESS                                                     | 扫描件失败被掩盖，且幂等卡死重试              |
| G6 | 无降级策略         | 任一环节失败即 FAILED，无部分成功/兜底                                                             | 表格识别失败会拖垮整篇文档                 |

---

## 四、样例 PDF 只读探针（职教园一期施工许可证.pdf）

> 探针方式：PDFBox 3.0.4 PDFTextStripper + PDFStreamEngine（DrawObject CTM 实际占位面积），只读。

| 指标   | 值                                             | 结论                       |
|------|-----------------------------------------------|--------------------------|
| 页数   | 1                                             | 单页                       |
| 页面尺寸 | 841×595 pt（A4 横版），旋转 0°                       | 横版证书                     |
| 文字层  | **367 字符**（非空）                                | 表格数据存在于 PDF 原生文字层        |
| 图片数量 | 2                                             | ——                       |
| 大图   | 3508×2480 px，占位 842.3×595.5 pt，**面积占比 1.002** | **整页背景图**（表格线 + 表头 + 版式） |
| 小图   | 992×992 px，占位 119×118 pt，面积占比 0.028           | 疑似印章/徽标（约 3% 页面）         |

文字层预览（PDFTextStripper 顺序输出，无结构）：

```
341602202210260101  亳州市谯城区住房和城乡建设局  2022年10月26日  亳州市教育局
亳州市职教园区项目(一期)1#教学楼、2#教学楼、3#教学楼、4#教学楼、1#宿舍、2#宿舍、
3#宿舍、4#宿舍、1#办公...
```

**验证结论**：与方案描述完全吻合——底层表格/表头由整页图片构成（面积占比 100.2%），表格数据（建设单位/项目名称/日期/证号等）在
PDF 文字层中，且文字层输出为无结构的流式串（字段间仅以空格分隔）。当前 PdfParser 只会得到这 367
字符的裸串，字段与值的空间对应关系全部丢失；这正是后续"图片表格结构恢复 + 坐标融合"（阶段 5-8）要解决的问题。该样例仅作真实测试样本，不做任何硬编码特判。

---

## 五、编译与测试基线

| 项      | 命令                                                                                                                                                                | 结果                                                   |
|--------|-------------------------------------------------------------------------------------------------------------------------------------------------------------------|------------------------------------------------------|
| 编译     | `mvn -q compile`                                                                                                                                                  | 通过（exit 0）                                           |
| 关键离线单测 | `mvn test -Dtest=PdfParserTest,DocumentChunkerTest,DocumentIngestionServiceImplTest,FileParserRegistryTest,ExcelParserTest,WordParserTest,AsyncParseExecutorTest` | 全部通过（exit 0，JUnit+Mockito 离线，不依赖 MySQL/Milvus/Redis） |

测试基线：现有测试共 18 个类（controller 4 / parser 5 / rag 6 / task 3），均离线可运行，新阶段测试遵循同一约束（OCR 用
MockOcrParser 桩）。

---

## 六、环境与资源约束

| 项            | 值                                                         |
|--------------|-----------------------------------------------------------|
| Java         | 21（Spring Boot 3.5.13，Spring AI 1.0.0 + Alibaba 1.0.0.2）  |
| PDFBox       | 3.0.4（pom 显式管理）；POI 5.3.0                                 |
| 部署约束         | 2 CPU / 8GB RAM / 无 GPU → OCR 并发必须 1，禁止整页高分辨率批量渲染         |
| OCR 选型（后续阶段） | OCRmyPDF + Tesseract（chi_sim），替换 PaddleOcrParser 桩为默认生产实现 |

---

## 七、阶段 0 验收自检

| 验收标准                    | 状态                               |
|-------------------------|----------------------------------|
| 1. PDF 现状解析方式已明确        | ✔ §二.1                           |
| 2. 扫描 PDF 当前为什么失败已明确    | ✔ §二.2（含"误标 SUCCESS + 幂等卡死"缺陷定位） |
| 3. 当前 chunk 如何生成已明确     | ✔ §二.3                           |
| 4. ParserDocument 字段已明确 | ✔ §二.4                           |
| 5. 必须兼容代码已明确            | ✔ §二.5（13 项接口冻结面）                |
| 6. 后续需修改代码已明确           | ✔ §二.6（按阶段映射的影响清单）               |
| 当前链路图已建立                | ✔ §一                             |
| 生产代码零修改                 | ✔（仅产出本分析文档）                      |

**结论：阶段 0 达到验收标准，可进入阶段 1（建立 PDF 内容检测层）。**
