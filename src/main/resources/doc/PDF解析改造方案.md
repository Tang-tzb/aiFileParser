# PDF解析改造方案

你现在作为本项目的【企业级 Java PDF 文档解析架构师 + RAG 工程师】，需要基于当前代码仓库，对现有“文件解析 → 文档结构化 → RAG
分块 → 向量化 → AI 字段抽取”链路进行一次完整的架构升级。

本次任务不是简单增加一个 OCR，而是要把当前 PDF 解析能力升级成一套能够自动适配以下多种 PDF 类型的统一文档解析架构：

​ \1. 纯文字 PDF

​ \2. 整页扫描图片 PDF

​ \3. 图片 + 文字混合 PDF

​ \4. 图片是表格/表头，文字层是表格数据的 PDF

​ \5. 图片包含正文，PDF 文字层只有局部文字的 PDF

​ \6. 正文是 PDF 文字，同时存在 Logo、印章、签字、附件图片

​ \7. 同一个 PDF 中不同页面属于不同类型

​ \8. 同一页面内部不同区域属于不同类型

本次改造必须重点参考当前项目中的真实文件样例：

D:\project\aiFileParser\uploads\2026\08\职教园一期施工许可证.pdf

该文件属于典型的混合型 PDF：底层表格和表头主要由图片构成，而表格内容存在于 PDF 文字层中。不能简单对整页重新 OCR，否则会破坏已有
PDF 文字质量，并产生重复识别、OCR 错字和资源浪费。

## **一、当前项目现状**

当前项目代码结构和主链路如下：

parser/

├── FileParser.java

├── FileParserRegistry.java

├── PdfParser.java

├── ExcelParser.java

├── WordParser.java

└── ocr/

├── OcrParser.java

└── PaddleOcrParser.java

当前 PdfParser 使用：

PDFBox Loader.loadPDF

+

PDFTextStripper

当前扫描版 PDF 解析结果为空。

OCR 目前只有接口和 PaddleOcrParser 桩实现，没有真正接入 OCR。

当前统一解析结果：

ParserDocument

├── content

└── metadata

当前 RAG：

DocumentIngestionService

↓

FileParserRegistry

↓

ParserDocument

↓

DocumentChunker

↓

Embedding

↓

Milvus

当前 DocumentChunker：

JTokkit

CL100K_BASE

chunk.size = 800 token

overlap = 200 token

当前 AI 字段抽取流程：

文件解析

→ chunk

→ Milvus

→ 按字段检索

→ Qwen-Plus

→ JSON

→ FieldSchemaValidator

→ Retry

现有异步任务进度：

0% PARSING

50% VECTORING

80% EXTRACTING

100% SUCCESS

-1 FAILED

以上现有业务能力必须保留。

## **二、本次改造目标**

最终必须实现：

PDF

↓

PDF 内容检测

↓

Page 级内容检测

↓

Region 级内容分析

↓

选择最合适的解析策略

↓

统一坐标系统

↓

文本 / 图片 / 表格结构融合

↓

Document AST

↓

清洗

↓

结构化 Markdown

↓

Hybrid Semantic Chunking

↓

Embedding

↓

Milvus

↓

AI 字段抽取

最重要的架构原则：

​ \1. OCR 不是 PDF 解析器本身，而是底层能力。

​ \2. PDFBox 负责解析 PDF 原生文字层及其坐标。

​ \3. OCR 只负责 PDF 中缺失的视觉文本信息。

​ \4. 图片负责视觉结构时，应使用图片分析恢复结构。

​ \5. 不允许对已经存在高质量文字层的页面无条件整页 OCR。

​ \6. 三种 PDF 类型最终必须输出同一种 Document AST。

​ \7. Chunker 不允许感知底层是 PDFBox、OCR 还是混合解析。

​ \8. Parser、Cleaner、Chunker 必须解耦。

​ \9. 必须保留页码、坐标、来源、解析方式、置信度等元数据。

​ \10. 解析失败不能直接导致整个业务链路失控，应提供降级策略。

## **三、OCR 技术选型要求**

本次改造如果需要 OCR，必须明确使用：

【Tesseract OCR + OCRmyPDF】

其中：

OCRmyPDF：

负责扫描 PDF 的 OCR 流程、生成文字层、页面预处理等。

Tesseract OCR：

负责真正的 OCR 文字识别。

中文必须支持：

chi_sim

不要继续使用当前的 PaddleOcrParser 桩实现作为主方案。

如果项目中为了后续扩展仍然保留 PaddleOCR 接口，可以保留 OcrParser 抽象，但默认生产实现必须采用：

OcrmyPdfTesseractParser

或者：

TesseractOcrParser

具体类名你根据项目现有代码风格决定。

要求：

OcrParser 不允许只有：

String recognize(File file)

这种过于简单的设计。

OCR 返回结果必须至少包含：

text

page

x

y

width

height

confidence

建议抽象成：

OcrResult

└── List

└── List

OcrWord：

text

x

y

width

height

confidence

如果 OCRmyPDF 生成文字层之后再通过 PDFBox 提取文字，也必须能够区分：

SOURCE = PDF_TEXT

SOURCE = OCR

禁止丢失来源信息。

## **四、PDF 类型检测架构**

不要直接给整个 PDF 只判断一次类型。

必须：

PDF 级别分析

+

Page 级别分析

至少设计：

PdfContentType：

TEXT_ONLY

IMAGE_ONLY

MIXED

EMPTY

PageContentType：

TEXT_ONLY

IMAGE_ONLY

MIXED

EMPTY

建议增加：

RegionType：

TEXT

TABLE

IMAGE

FIGURE

STAMP

SIGNATURE

HEADER

FOOTER

UNKNOWN

必须设计：

PdfAnalyzer

PageAnalyzer

PageProfile

PageProfile 至少包含：

pageNumber

pageWidth

pageHeight

textCount

imageCount

textAreaRatio

imageAreaRatio

hasFullPageImage

hasLargeImage

hasTableLikeRegion

contentType

判断规则不要只看：

imageCount > 0

因为 Logo、印章、小图片不能把普通文字 PDF 判断成 IMAGE_ONLY。

整页图片判断建议考虑：

图片面积 / 页面面积

以及图片位置是否接近页面边界。

必须做到：

一个 PDF 可以同时存在：

Page 1 → TEXT_ONLY

Page 2 → IMAGE_ONLY

Page 3 → MIXED

Page 4 → TEXT_ONLY

不能假设一个 PDF 只有一种类型。

## **五、PageParser 路由架构**

增加：

PageParser

接口：

PageDocument parse(PageContext context)

实现：

TextPageParser

ImagePageParser

MixedPageParser

EmptyPageParser

增加：

PageParserRouter

根据 PageProfile 自动选择 Parser。

路由：

TEXT_ONLY

→ TextPageParser

IMAGE_ONLY

→ ImagePageParser

MIXED

→ MixedPageParser

EMPTY

→ EmptyPageParser

Parser 不允许直接返回 String。

统一返回：

PageDocument

例如：

PageDocument

├── pageNumber

├── contentType

├── pageSize

└── elements

## **六、纯文字 PDF 的处理**

TEXT_ONLY：

使用：

PDFBox

读取 PDF 原生文字。

必须使用：

PDFTextStripper

或

自定义 PDFTextStripper 实现

提取：

TextPosition

而不是仅：

extractText()

必须保留：

text

x

y

width

height

fontSize

fontName

page

目的：

后续标题识别、段落识别、表格识别、坐标匹配都依赖这些信息。

不要在 PdfParser 这一层直接把结构信息全部拍扁成一个 String。

## **七、整页图片 PDF 的处理**

IMAGE_ONLY：

不要使用 PDFBox TextStripper，因为本身没有有效文字层。

处理：

PDF Page

↓

Render Page Image

↓

OCRmyPDF / Tesseract

↓

OcrResult

↓

LayoutAnalyzer

↓

Document AST

OCR 必须返回文字坐标。

中文使用：

chi_sim

应支持：

倾斜校正

旋转页面检测

基础图像预处理

注意：

对于资源受限环境：

2 CPU

8 GB RAM

无 GPU

默认 OCR 并发必须控制为：

1

不要为每一页创建无限并发 OCR 任务。

## **八、混合 PDF 的处理，这是本次改造重点**

MIXED 页面不得默认整页 OCR。

必须进一步分析：

PDF 原生文字层

+

PDF 图片层

然后进行：

Image Analysis

+

PDF Text Analysis

最后坐标融合。

以：

《职教园一期施工许可证.pdf》

为重点测试样例。

该样例特点：

表格线 / 表头属于图片

表格数据属于 PDF 原生文字层

因此：

图片：

负责恢复表格结构

PDFBox：

负责提取文字内容及坐标

最后：

Coordinate Fusion

形成：

TableGrid

+

TableCell

例如：

TableCell：

rowIndex

columnIndex

rowSpan

colSpan

boundingBox

header

value

source

confidence

最终恢复：

建设单位 → 亳州市教育局

项目名称 → 亳州市职教园区项目（一期）……

建设地点 → 南地块……

北地块……

建筑面积 → 170649.08平方米

不能把这些内容简单恢复成：

建设单位

亳州市教育局

项目名称

……

必须建立结构关系。

## **九、图片表格结构恢复**

增加：

TableStructureRecognizer

建议：

HybridTableRecognizer

内部：

ImageTableRecognizer

+

CoordinateTableRecognizer

不要假设图片一定有明显表格线。

情况一：

图片包含清晰横线和竖线：

使用图像分析恢复：

X lines

Y lines

Intersection

Cell

情况二：

没有完整边框：

结合：

文字坐标

X 聚类

Y 聚类

行间距

列间距

区域关系

恢复表格结构。

必须支持：

rowSpan

colSpan

必须支持一个字段多行。

例如：

建设地点

可能对应：

多行 PDF TextPosition

最终：

一个 TableCell

而不是多个字段。

## **十、图片表头 + PDF文字内容的融合**

这是必须重点实现的能力。

假设：

图片：

建设单位

项目名称

建设地点

建筑面积

PDF文字：

亳州市教育局

亳州市职教园区项目……

南地块……

170649.08平方米

处理流程：

图片

↓

检测 Header Cell

↓

只对 Header Region OCR

↓

识别表头文字

PDFBox

↓

提取 PDF TextPosition

↓

根据坐标匹配 Cell

最终：

TableCell.header

TableCell.value

不要整页重新 OCR。

OCR 只对：

缺少文字层的视觉区域

进行补充。

## **十一、坐标系统设计**

必须建立统一：

CoordinateTransformer

必须解决：

PDF 坐标

+

图片像素坐标

+

OCR 坐标

之间的转换。

因为：

PDF 可能：

595 × 842

图片可能：

2480 × 3508

必须能够：

imageToPdf()

pdfToImage()

统一为：

DocumentCoordinateSystem

所有：

Text

Image

Table

Cell

OCR

最终都使用同一个 BoundingBox。

BoundingBox：

x

y

width

height

并提供：

contains()

overlap()

intersection()

iou()

expand()

方法。

## **十二、Region 级解析**

不要停留在：

PDF → Page

还要支持：

Page → Region

例如：

Page

├── Region 1 TEXT

├── Region 2 TABLE

├── Region 3 IMAGE

└── Region 4 STAMP

RegionRouter 根据 RegionType 选择处理方式。

例如：

TEXT

→ PDFBox

TABLE

→ TableStructureRecognizer

IMAGE

→ Tesseract

STAMP

→ 可识别但默认不进入正文 RAG

这样以后：

正文 PDF 文字

+

图片附件

+

盖章

能够正确处理。

## **十三、Document AST 设计**

这是本次架构改造的核心。

必须增加统一的：

DocumentAst

例如：

DocumentAst

├── documentId

├── fileName

├── metadata

└── pages

PageNode

├── pageNumber

└── nodes

DocumentNode 类型至少支持：

DOCUMENT

TITLE

SECTION

PARAGRAPH

TABLE

TABLE_ROW

TABLE_CELL

KEY_VALUE

LIST

IMAGE

HEADER

FOOTER

STAMP

SIGNATURE

所有不同 PDF 类型最后必须统一进入：

DocumentAst

不能：

TEXT_ONLY 返回 String

IMAGE_ONLY 返回 OCR String

MIXED 返回另外一种 DTO

这样会导致后续 Chunker 和 RAG 层出现三套逻辑。

## **十四、统一的数据来源标识**

每个 DocumentNode / DocumentElement 必须能够记录：

source：

PDF_TEXT

OCR

IMAGE

FUSION

例如：

TableCell.value：

source = PDF_TEXT

TableCell.header：

source = OCR

最终融合节点：

source = FUSION

同时记录：

confidence

推荐：

0.0 ~ 1.0

例如：

PDF 原生文字：

confidence = 1.0

OCR：

使用 OCR 返回 confidence

Fusion：

根据：

坐标匹配程度

OCR confidence

字段结构

计算最终 confidence。

## **十五、文本清洗架构**

不要设计成一个：

cleanText(String)

必须：

DocumentCleaner

拆分：

CharacterCleaner

LineCleaner

OcrErrorCleaner

HeaderFooterCleaner

DuplicateCleaner

TableCleaner

清洗必须发生在：

Document AST

而不是 OCR 刚结束时就全部拍扁。

## **十六、换行清洗**

必须避免：

text.replace("\n", "")

因为这会破坏真正的段落结构。

需要判断：

是否同一个 Cell

是否同一个 Paragraph

是否同一个 Title

上一行是否以标点结束

下一行是否为标题

是否跨区域

是否跨 Cell

例如：

建设地点：

南地块位于养生大道以南，

古井大道以东；

北地块位于谯城经开区古井大道以东，

桐花路（原创业西路）以南

必须恢复成一个：

TableCell

而不是：

4 个 Chunk。

## **十七、页眉页脚处理**

增加：

HeaderFooterDetector

判断：

相同内容

+

固定页面顶部/底部

+

跨多个页面重复出现

则认为：

HEADER / FOOTER

默认不进入 RAG 正文 Chunk。

保留到 metadata 即可。

## **十八、OCR 清洗**

对于 OCR 数字误识别，例如：

17O649.08

可能恢复为：

170649.08

但禁止全局：

O → 0

必须结合：

字段类型

上下文

正则

数字格式

只处理高置信度场景。

低置信度不要强行修改。

## **十九、Markdown 渲染**

Document AST 清洗完成后，再转换：

Markdown

增加：

MarkdownRenderer

例如：

# **施工许可证**

## **基本信息**

| **字段** | **内容**          |
|--------|-----------------|
| 建设单位   | 亳州市教育局          |
| 项目名称   | 亳州市职教园区项目（一期）…… |
| 建设地点   | 南地块……；北地块……     |
| 建筑面积   | 170649.08平方米    |

注意：

Markdown 是输出格式 / 中间交换格式。

不能使用 Markdown 作为底层解析模型。

正确：

PDF

→ Document AST

→ Markdown

→ Chunk

错误：

PDF

→ Markdown

→ 再反向分析结构

→ Chunk

## **二十、语义切片架构改造**

当前：

JTokkit

800 token

200 overlap

保留 Token 计算能力，但不要继续使用单纯滑动窗口作为唯一切片策略。

增加：

HybridSemanticChunker

切片优先级：

第一层：

结构边界

第二层：

语义边界

第三层：

长度约束

优先级：

标题

章节

段落

表格

KeyValue

列表

句子

Token 长度

最终：

结构切片

+

语义相似度

+

Token 限制

## **二十一、表格 Chunk 策略**

表格默认：

一个完整表格 = 一个 Chunk

禁止：

一行表格 = 一个 Chunk

如果表格过大：

按：

表头

+

N 行数据

切分。

每个 Chunk 必须重复完整表头。

例如：

Chunk 1：

表名：项目投资明细

表头：

项目编号 | 项目名称 | 投资金额 | 建设单位

数据：

1 | A | ...

2 | B | ...

Chunk 2：

表名：项目投资明细

表头：

项目编号 | 项目名称 | 投资金额 | 建设单位

数据：

31 | ...

32 | ...

不能产生脱离上下文的：

31 | XX | 3000万

## **二十二、Chunk Metadata**

RagChunk 必须至少保留：

documentId

chunkId

fileId

fileName

pageStart

pageEnd

titlePath

chunkType

sequence

totalChunks

content

sourceType

建议：

bbox

confidence

chunkType：

TITLE

PARAGRAPH

TABLE

KEY_VALUE

LIST

MIXED

## **二十三、标题上下文增强**

不要只保存：

项目总建筑面积为170649.08平方米。

应该保存：

文档：

职教园一期施工许可证

章节：

项目基本信息 > 建设规模

内容：

项目总建筑面积为170649.08平方米。

这样提高 Embedding 和召回效果。

## **二十四、不要让 Chunker 依赖底层解析方式**

Chunker 不允许出现：

if (ocr)

if (pdf)

if (mixed)

Chunker 只处理：

DocumentAst

Parser 层负责：

“这是什么结构”

Cleaner 负责：

“内容是否干净”

Chunker 负责：

“哪些内容应该一起被召回”

三层必须彻底解耦。

## **二十五、Document Parser 整体接口设计**

建议：

DocumentParser

负责完整文档解析：

parse(File file)

内部：

PdfAnalyzer

→ PageAnalyzer

→ PageParserRouter

→ PageParser

→ RegionParser

→ DocumentAst

不要让：

PdfParser

承担所有职责。

原来的：

PdfParser

可以保留作为：

PdfDocumentParser

但需要重构为编排器。

## **二十六、推荐代码结构**

建议最终形成：

parser/

├── FileParser.java

├── FileParserRegistry.java

│

├── pdf/

│ ├── PdfDocumentParser.java

│ ├── PdfAnalyzer.java

│ ├── PageAnalyzer.java

│ ├── PageParserRouter.java

│ │

│ ├── page/

│ │ ├── PageParser.java

│ │ ├── TextPageParser.java

│ │ ├── ImagePageParser.java

│ │ ├── MixedPageParser.java

│ │ └── EmptyPageParser.java

│ │

│ ├── region/

│ │ ├── RegionAnalyzer.java

│ │ ├── RegionRouter.java

│ │ └── RegionType.java

│ │

│ ├── layout/

│ │ ├── LayoutAnalyzer.java

│ │ ├── TextBlockBuilder.java

│ │ ├── TableStructureRecognizer.java

│ │ ├── HybridTableRecognizer.java

│ │ ├── TableGrid.java

│ │ └── CoordinateMatcher.java

│ │

│ └── coordinate/

│ ├── BoundingBox.java

│ └── CoordinateTransformer.java

│

├── ocr/

│ ├── OcrParser.java

│ ├── OcrResult.java

│ ├── OcrPage.java

│ ├── OcrWord.java

│ ├── TesseractOcrParser.java

│ └── OcrmyPdfService.java

│

├── structure/

│ ├── TitleRecognizer.java

│ ├── ParagraphRecognizer.java

│ ├── KeyValueRecognizer.java

│ ├── HeaderFooterDetector.java

│ └── TableRecognizer.java

│

├── model/

│ ├── DocumentAst.java

│ ├── PageNode.java

│ ├── DocumentNode.java

│ ├── TableNode.java

│ ├── TableRowNode.java

│ ├── TableCellNode.java

│ └── KeyValueNode.java

│

└── cleaner/

├── DocumentCleaner.java

├── CharacterCleaner.java

├── LineCleaner.java

├── OcrErrorCleaner.java

├── HeaderFooterCleaner.java

└── TableCleaner.java

rag/

├── DocumentChunker.java

├── HybridSemanticChunker.java

├── StructuralChunker.java

└── SemanticChunker.java

## **二十七、兼容现有 ParserDocument**

当前项目已有：

ParserDocument

+

ParserDocumentMetadata

不要直接粗暴删除。

应该考虑：

ParserDocument

├── content

├── ast

└── metadata

或者：

ParserDocument

内部增加：

DocumentAst ast

content 可以由 MarkdownRenderer 最终生成。

这样旧业务接口保持兼容，新业务使用 AST。

必须尽量减少对：

DocumentIngestionService

FieldExtractorService

VectorStoreService

的侵入。

## **二十八、兼容现有 RAG 流程**

保持：

DocumentIngestionService

作为入库编排者。

最终：

ingest

→ parse

→ clean

→ chunk

→ embedding

→ milvus

现有：

fileId filter

必须保留。

Milvus 现有：

aifp_doc_chunks

必须继续支持。

Embedding 当前：

DashScope text-embedding-v2

1536 维

原则上不修改。

## **二十九、幂等与状态流转不能破坏**

必须保留：

UPLOADED

→ PARSING

→ VECTORING

→ EXTRACTING

→ SUCCESS / FAILED

当前：

FileStatus.SUCCESS

用于幂等。

本次改造不得破坏。

如果 OCR 失败：

必须：

markFailed

同时记录：

errorMessage

建议错误信息包含：

PDF 类型

页码

Parser

OCR

Region

具体异常

方便排查。

## **三十、2C8G 无 GPU 环境要求**

当前部署环境：

2 CPU

8GB RAM

无 GPU

因此必须控制资源。

要求：

​ \1. OCR 并发默认 1

​ \2. 不允许同时处理多个大 PDF

​ \3. 页面级解析尽量流式

​ \4. 不要一次性将所有页面的大图全部加载到内存

​ \5. OCR 图片处理完成后及时释放

​ \6. 不允许为了表格识别而默认把所有 PDF 页转成高分辨率图片

​ \7. 有原生文字层时优先使用 PDFBox

​ \8. OCR 只处理缺少文字的区域

​ \9. 混合 PDF 不能默认整页 OCR

## **三十一、异常降级策略**

必须设计降级。

例如：

TEXT_ONLY

PDFBox 成功

→ 正常

TEXT_ONLY

PDFBox 文字量异常少

→ 尝试 OCR

IMAGE_ONLY

OCR 成功

→ 正常

MIXED

PDFBox + Image Analysis 成功

→ Fusion

MIXED

表格检测失败

→ 保留 OCR/PDF 原始文本 + 降级普通段落解析

OCR 失败

→ 保留可读取的 PDF 原生文字

不能因为表格识别失败就让整个 PDF 解析失败。

## **三十二、测试要求**

必须新增测试。

至少测试以下 4 类 PDF：

Test 1：

纯文字 PDF

预期：

TEXT_ONLY

PDFBox

不调用 OCR

Test 2：

整页图片 PDF

预期：

IMAGE_ONLY

调用 Tesseract/OCRmyPDF

Test 3：

当前上传样例：

职教园一期施工许可证.pdf

预期：

MIXED

图片：

表格结构 / 表头

PDF：

文字数据

最终：

TableNode

+

TableCell

必须至少正确验证：

建设单位

项目名称

建设地点

建筑面积

施工单位

监理单位

测试不能只验证“不报错”，还必须验证结构。

Test 4：

正文文字 + Logo/印章/签字图片

预期：

正文正常进入 RAG

Logo/印章默认不污染正文 Chunk。

## **三十三、测试必须离线**

遵循当前项目要求：

JUnit

Mockito

尽量不依赖：

MySQL

Milvus

Redis

外部 OCR 服务

OCR 部分提供：

MockOcrParser

真实 OCR 可以进行集成测试。

## **三十四、代码质量要求**

严格遵守：

​ \1. 单方法原则上 ≤ 50 行

​ \2. Controller 不写业务

​ \3. 复杂逻辑拆分成 Service / Strategy

​ \4. 使用接口解耦

​ \5. 避免 if/else 巨型判断

​ \6. 优先使用 Strategy + Registry + Router

​ \7. 所有核心类增加 JavaDoc

​ \8. 关键算法增加中文注释

​ \9. 不允许为了完成任务制造大量重复工具类

​ \10. 不允许修改与本任务无关的业务代码

​ \11. 不允许删除现有能力

​ \12. 不允许硬编码具体文件名称作为特殊逻辑

​ \13. 不能对“职教园一期施工许可证.pdf”做特判

## **三十五、禁止事项**

禁止：

​ \1. 直接把 PdfParser 改成“整页 OCR”

​ \2. 所有 PDF 都走 OCR

​ \3. 用 OCR 代替 PDFBox 原生文字解析

​ \4. OCR 后直接 String.replace 全部换行

​ \5. 只保留 String 不保留坐标

​ \6. 表格按照固定字符长度切片

​ \7. 每一行表格作为独立 Chunk

​ \8. 把印章、Logo、签字全部进入 RAG

​ \9. 用 LLM 直接读取整个 PDF 再自己猜表格

​ \10. 为了演示而硬编码本样例字段

​ \11. 删除现有 ParserDocument

​ \12. 破坏现有 Excel/Word Parser

​ \13. 破坏现有异步 SSE 任务链路

​ \14. 破坏 Milvus fileId 过滤

​ \15. 引入 GPU-only OCR 方案

​ \16. 默认高并发 OCR

## **三十六、最终交付要求**

不要只给修改建议。

直接修改当前代码，并完成：

​ \1. 新 PDF 检测器

​ \2. PageAnalyzer

​ \3. PageParserRouter

​ \4. TextPageParser

​ \5. ImagePageParser

​ \6. MixedPageParser

​ \7. OcrParser 新接口

​ \8. Tesseract/OCRmyPDF 实现

​ \9. 坐标模型

​ \10. TableGrid

​ \11. TableCell

​ \12. Mixed PDF 坐标融合

​ \13. Document AST

​ \14. Cleaner

​ \15. MarkdownRenderer

​ \16. HybridSemanticChunker

​ \17. 兼容 ParserDocument

​ \18. RAG 入库流程适配

​ \19. 单元测试

​ \20. 配置文件

​ \21. Docker/OCR 部署说明

​ \22. README 更新

## **三十七、配置要求**

新增配置示例：

document:

parser:

pdf:

enabled: true

mixed-mode: true

ocr:

enabled: true

engine: tesseract

language: chi_sim

concurrency: 1

timeout-seconds: 300

rag:

chunk:

strategy: hybrid

size: 800

overlap: 200

table-max-rows: 50

阈值例如：

pdf:

detector:

full-image-ratio: 0.85

text-min-count: 5

table:

row-y-threshold: 8

coordinate-tolerance: 5

这些都应该配置化，不能散落硬编码。

## **三十八、最终验收标准**

必须满足：

【PDF 纯文字】

PDFBox

→ TextPosition

→ Document AST

→ Chunk

不调用 OCR。

【整页扫描 PDF】

Render

→ Tesseract/OCRmyPDF

→ OcrWord + Coordinate

→ Document AST

→ Chunk

【混合 PDF】

PDFBox + Image

→ Region Detection

→ 部分 OCR

→ Coordinate Fusion

→ Document AST

→ Chunk

【图片表头 + PDF 文字数据】

Image

→ TableGrid/Header OCR

PDF TextPosition

→ Cell Match

最终：

TableCell.header

+

TableCell.value

【表格】

完整表格优先一个 Chunk。

大表格：

表头 + N 行

并重复表头。

【语义切片】

不能只是：

800 token + 200 overlap

必须：

结构 + 语义 + 长度

【RAG】

每个 Chunk 至少携带：

fileId

fileName

pageStart

pageEnd

titlePath

chunkType

chunkIndex

content

## **三十九、分阶段交付边界**

本次 PDF 解析架构改造必须采用【阶段化交付】方式实施。

禁止一次性修改全部模块、一次性大范围重构。

每个阶段必须满足：

​ \1. 本阶段目标明确

​ \2. 本阶段修改范围明确

​ \3. 本阶段不修改的范围明确

​ \4. 本阶段代码可以独立编译

​ \5. 本阶段已有能力不能被破坏

​ \6. 本阶段完成后必须执行对应测试

​ \7. 未达到阶段验收标准，不得进入下一阶段

​ \8. 不允许为了“后续阶段方便”提前大规模修改后续模块

每个阶段完成后，必须输出：

​ ● 本阶段修改文件

​ ● 本阶段新增类

​ ● 本阶段核心设计

​ ● 测试结果

​ ● 是否达到阶段验收标准

​ ● 下一阶段可以依赖哪些接口

==================================================

# **阶段 0：现状基线与影响分析**

### **目标**

在任何代码修改之前，完整理解当前项目。

### **本阶段允许修改**

原则上不修改业务代码。

只允许：

​ ● 阅读源码

​ ● 梳理依赖

​ ● 建立现状分析

​ ● 必要时增加测试基线，但不能修改生产逻辑

### **必须分析**

至少确认：

parser/

FileParser

FileParserRegistry

PdfParser

ExcelParser

WordParser

ocr/

document/

ParserDocument

ParserDocumentMetadata

rag/

DocumentChunker

DocumentIngestionService

EmbeddingService

VectorStoreService

service/

FieldExtractorService

task/

AsyncParseExecutor

以及：

​ ● FileStatus

​ ● TaskProgress

​ ● Milvus metadata

​ ● 当前 chunk metadata

​ ● 当前 PDF 解析入口

​ ● 当前 AI 字段抽取依赖

### **必须建立当前链路图**

必须明确：

上传

→ 文件保存

→ parser

→ ParserDocument

→ chunk

→ embedding

→ Milvus

→ retrieve

→ LLM

→ ExtractionResult

### **本阶段禁止**

禁止：

​ ● 重构 Parser

​ ● 引入 OCR

​ ● 修改 Chunker

​ ● 修改 Milvus

​ ● 修改字段抽取

### **阶段验收标准**

必须明确当前系统：

​ \1. PDF 现状解析方式

​ \2. 扫描 PDF 当前为什么失败

​ \3. 当前 chunk 如何生成

​ \4. 当前 ParserDocument 有哪些字段

​ \5. 哪些代码必须兼容

​ \6. 哪些代码后续需要修改

==================================================

# **阶段 1：建立 PDF 内容检测层**

### **目标**

只解决一个问题：

【判断 PDF 每一页是什么类型】

支持：

TEXT_ONLY

IMAGE_ONLY

MIXED

EMPTY

### **本阶段修改范围**

新增：

PdfAnalyzer

PageAnalyzer

PageProfile

PdfContentType

PageContentType

必要时新增：

PdfInspectionResult

### **本阶段不修改**

不得修改：

​ ● OCR

​ ● DocumentChunker

​ ● Embedding

​ ● Milvus

​ ● AI 抽取

​ ● ExcelParser

​ ● WordParser

### **本阶段必须实现**

以 Page 为最小检测单位。

能够识别：

Page 1 → TEXT_ONLY

Page 2 → IMAGE_ONLY

Page 3 → MIXED

### **阶段验收**

至少准备三类测试 PDF：

​ \1. 纯文字 PDF

​ \2. 整页扫描 PDF

​ \3. 图片 + 文字 PDF

验证：

​ ● TEXT_ONLY 判断正确

​ ● IMAGE_ONLY 判断正确

​ ● MIXED 判断正确

​ ● Logo 不会导致 TEXT_ONLY 被误判为 MIXED

​ ● 空白页可以识别 EMPTY

==================================================

# **阶段 2：建立统一 PDF 页面解析接口**

### **目标**

把：

“PDF 类型判断”

和：

“PDF 内容解析”

分离。

### **本阶段新增**

PageParser

TextPageParser

ImagePageParser

MixedPageParser

EmptyPageParser

PageParserRouter

PageDocument

### **统一接口**

建议：

PageDocument parse(PageContext context)

### **本阶段暂时要求**

先建立接口和路由，不要求完整实现所有高级结构识别。

TEXT_ONLY：

可以先使用 PDFBox。

IMAGE_ONLY：

可以先输出明确的待解析结构，不要求此阶段完成真实 OCR。

MIXED：

可以先建立处理骨架。

### **本阶段禁止**

不得进入：

​ ● TableGrid

​ ● OpenCV 表格识别

​ ● OCR 表头

​ ● Document AST 完整设计

​ ● Semantic Chunker

### **阶段验收**

必须可以证明：

TEXT_ONLY

→ TextPageParser

IMAGE_ONLY

→ ImagePageParser

MIXED

→ MixedPageParser

EMPTY

→ EmptyPageParser

并且：

PageParserRouter

不出现大型 if/else。

==================================================

# **阶段 3：PDFBox 原生文字结构化**

### **目标**

把当前：

PDF → String

升级：

PDF → TextPosition → TextBlock

### **本阶段实现**

PdfTextExtractor

PdfText

TextBlock

BoundingBox

必要的：

PdfCoordinateConverter

### **必须保留**

至少：

text

x

y

width

height

fontSize

fontName

page

### **必须实现**

同页文字：

按照 Y 坐标聚合

→ 行

按照 X 坐标排序

→ 行内文字

多行继续聚合

→ TextBlock

### **本阶段允许修改**

PdfParser / TextPageParser

使其使用新的结构化文本能力。

### **本阶段不修改**

不得：

​ ● 接入 OCR

​ ● 修改 RAG Chunker

​ ● 修改 Milvus

​ ● 修改 AI 抽取

### **阶段验收**

对纯文字 PDF：

必须能够获得：

Page

→ TextBlock

→ BoundingBox

且原有：

ParserDocument.content

继续可以生成。

==================================================

# **阶段 4：接入 OCR——只解决 IMAGE_ONLY**

### **目标**

真正接入 OCR，但本阶段只处理：

IMAGE_ONLY

### **OCR 技术**

明确使用：

【OCRmyPDF + Tesseract OCR】

中文：

chi_sim

OCRmyPDF 负责：

​ ● PDF OCR 流程

​ ● 页面预处理

​ ● OCR 文字层生成

Tesseract：

负责实际文字识别。

### **必须抽象**

OcrParser

OcrResult

OcrPage

OcrWord

### **OcrWord 至少包含**

text

x

y

width

height

confidence

page

source

source：

OCR

### **本阶段实现**

ImagePageParser：

PDF

→ 图片

→ OCR

→ OcrResult

→ PageDocument

### **本阶段禁止**

不得处理：

​ ● 混合 PDF 坐标融合

​ ● 表格图片结构

​ ● 表头融合

​ ● TableGrid

这些必须留到后续阶段。

### **资源限制**

当前机器：

2 CPU

8 GB

无 GPU

因此：

OCR concurrency = 1

禁止：

​ ● 多页无限并发

​ ● 一次性加载所有页面图片

​ ● 默认超高分辨率渲染

### **阶段验收**

整页扫描 PDF：

必须能够：

PDF

→ OCRmyPDF/Tesseract

→ OcrWord

→ PageDocument

且：

OCR 失败能够返回明确错误。

==================================================

# **阶段 5：统一坐标系统**

### **目标**

解决：

PDF 坐标

+

图片像素坐标

+

OCR 坐标

无法直接比较的问题。

### **新增**

BoundingBox

CoordinateTransformer

CoordinateSystem

### **必须支持**

imageToPdf()

pdfToImage()

normalize()

### **BoundingBox 至少支持**

contains()

overlap()

intersection()

iou()

expand()

### **阶段验收**

给定：

PDF 595×842

图片 2480×3508

能够正确转换坐标。

并能够验证：

图片中的某个区域

与：

PDF TextPosition

在统一坐标系中空间位置一致。

==================================================

# **阶段 6：混合 PDF 融合**

### **目标**

正式解决：

【图片 + PDF 原生文字】

特别是当前：

《职教园一期施工许可证.pdf》

### **本阶段是核心阶段**

处理：

Image

+

PDFBox TextPosition

### **新增**

MixedPageParser

CoordinateMatcher

RegionAnalyzer

RegionType

### **处理逻辑**

MIXED

→ 分析图片区域

→ 分析 PDF 文字区域

→ 坐标统一

→ 空间融合

### **必须支持**

TEXT region

TABLE region

IMAGE region

STAMP

SIGNATURE

UNKNOWN

### **关键原则**

禁止：

MIXED → 整页 OCR

必须：

已有 PDF 文字层

→ PDFBox

缺少文字的视觉区域

→ OCR

### **当前施工许可证**

必须能够识别为：

MIXED

并验证：

PDF 原生文字可以被保留下来。

### **本阶段暂不要求**

表格完整 Cell 恢复可以下一阶段完成。

本阶段重点：

完成：

PDF Text

+

Image Region

统一空间模型。

==================================================

# **阶段 7：表格结构恢复**

### **目标**

解决：

图片是表格线/表头

+

PDF 文字是数据

这一类问题。

### **新增**

TableStructureRecognizer

HybridTableRecognizer

ImageTableRecognizer

CoordinateTableRecognizer

TableGrid

TableRow

TableCell

### **两种识别方式**

方式一：

有明显表格线

图片

→ 横线检测

→ 竖线检测

→ 交点

→ Grid

→ Cell

方式二：

没有明显表格线

使用：

X 聚类

Y 聚类

行距

列距

文字位置

恢复：

TableGrid

### **TableCell 必须支持**

rowIndex

columnIndex

rowSpan

colSpan

boundingBox

header

value

source

confidence

### **阶段验收**

以：

《职教园一期施工许可证.pdf》

为验收样例。

至少验证：

建设单位

项目名称

建设地点

建筑面积

施工单位

监理单位

可以进入正确 TableCell。

特别要求：

建设地点这种多行数据：

必须被识别成一个 Cell。

不能拆成多个字段。

==================================================

# **阶段 8：图片表头识别与 Cell Fusion**

### **目标**

解决：

表头在图片

数据在 PDF 文字层

### **处理**

图片表头区域

→ OCR

OCR：

Tesseract chi_sim

→ Header

PDFBox：

→ TextPosition

坐标匹配：

→ Cell

最终：

TableCell

header

+

value

### **重要要求**

这里只 OCR：

【缺少文字层的 Header Region】

不能整页 OCR。

### **阶段验收**

必须能够形成：

建设单位

→ 亳州市教育局

项目名称

→ 亳州市职教园区项目……

建设地点

→ 南地块……

北地块……

建筑面积

→ 170649.08平方米

### **低置信度**

如果：

confidence < 配置阈值

不得强制修正。

允许：

进入 fallback。

==================================================

# **阶段 9：Document AST**

### **目标**

让：

TEXT_ONLY

IMAGE_ONLY

MIXED

最终统一。

### **新增**

DocumentAst

PageNode

DocumentNode

TableNode

TableRowNode

TableCellNode

KeyValueNode

ParagraphNode

TitleNode

### **统一输出**

三类 PDF：

TEXT_ONLY

→ DocumentAst

IMAGE_ONLY

→ DocumentAst

MIXED

→ DocumentAst

### **核心原则**

后续 Cleaner / Markdown / Chunker

都只能依赖：

DocumentAst

禁止再依赖：

PDFBox

OCR

### **阶段验收**

能够把三类 PDF 转成：

统一 DocumentAst

结构完整。

==================================================

# **阶段 10：清洗**

### **目标**

清洗结构化内容，而不是简单清洗 String。

### **新增**

DocumentCleaner

CharacterCleaner

LineCleaner

OcrErrorCleaner

HeaderFooterCleaner

DuplicateCleaner

TableCleaner

### **必须解决**

​ ● 不可见字符

​ ● 全角空格

​ ● 重复空白

​ ● OCR 数字错误

​ ● 错误换行

​ ● Header/Footer

​ ● 重复内容

​ ● 表格多行

### **禁止**

禁止：

text.replace(“\n”, “”)

禁止：

全局 O → 0

### **阶段验收**

原始：

南地块位于养生大道以南，

古井大道以东；北地块……

最终：

作为同一个 Cell / Paragraph。

==================================================

# **阶段 11：Markdown**

### **目标**

把：

DocumentAst

转换为：

Markdown

### **新增**

MarkdownRenderer

### **要求**

TableNode：

渲染为 Markdown Table

KeyValue：

可以渲染为：

字段：值

Paragraph：

普通 Markdown 段落

Title：

Markdown 标题

### **本阶段不允许**

不要通过 Markdown 重新恢复结构。

结构必须来自：

DocumentAst。

### **阶段验收**

当前施工许可证可以生成结构化 Markdown。

==================================================

# **阶段 12：Hybrid Semantic Chunking**

### **目标**

升级当前：

800 token / 200 overlap

滑动窗口模式。

### **新增**

HybridSemanticChunker

StructuralChunker

SemanticChunker

### **切片优先级**

标题

章节

段落

Table

KeyValue

List

Sentence

Token Length

### **表格规则**

完整表格：

优先一个 Chunk。

大表格：

表头

+

N 行

表头必须重复。

### **Chunk Metadata**

至少：

fileId

fileName

pageStart

pageEnd

titlePath

chunkType

chunkIndex

totalChunks

content

可增加：

bbox

confidence

sourceType

### **阶段验收**

验证：

​ \1. 普通正文不会被随意切断

​ \2. 标题与正文保持关系

​ \3. KeyValue 不被无意义拆分

​ \4. 表格不按行随机拆分

​ \5. 大表格重复表头

​ \6. Chunk 带完整 metadata

==================================================

# **阶段 13：接入现有 RAG**

### **目标**

把新的：

DocumentAst

+

HybridChunker

正式接入：

DocumentIngestionService

最终：

ingest

→ parse

→ clean

→ chunk

→ embedding

→ milvus

### **必须保持**

FileStatus：

UPLOADED

→ PARSING

→ VECTORING

→ EXTRACTING

→ SUCCESS / FAILED

必须保持：

fileId filter

必须保持：

Milvus collection：

aifp_doc_chunks

必须保持：

DashScope text-embedding-v2

1536 维。

### **本阶段禁止**

不允许：

​ ● 更换 Embedding 模型

​ ● 更换 Milvus

​ ● 重写 FieldExtractorService

​ ● 修改 Prompt Schema

​ ● 修改 AI Retry 逻辑

除非为了兼容新 Chunk metadata 确有必要。

==================================================

# **阶段 14：AI 字段抽取验证**

### **目标**

验证整个改造没有破坏：

RAG → AI 填报

### **测试链路**

文件：

职教园一期施工许可证

→ Parser

→ AST

→ Chunk

→ Embedding

→ Milvus

→ 字段 Query

→ Qwen-Plus

→ ExtractionResult

### **至少验证**

建设单位

项目名称

建设地点

建筑面积

施工单位

监理单位

### **必须验证**

fileId filter

不能跨文件召回。

### **本阶段重点**

不是修改 AI 抽取逻辑。

而是验证：

新 Parser / Chunker

没有破坏现有抽取。

==================================================

# **阶段 15：回归与性能验收**

### **必须准备至少 5 类测试样本**

​ \1. 纯文字 PDF

​ \2. 整页扫描 PDF

​ \3. 图片 + 文字 PDF

​ \4. 图片表格 + PDF 文字数据

​ \5. 正文 + Logo/印章/签字

### **再增加：**

同一个 PDF：

Page 1 → TEXT_ONLY

Page 2 → IMAGE_ONLY

Page 3 → MIXED

验证 Page Router。

### **性能要求**

当前：

2 CPU

8GB RAM

无 GPU

默认：

OCR 并发 = 1

必须测试：

小文件

中等文件

多页 PDF

观察：

CPU

内存

OCR 时间

解析时间

### **必须验证**

不能因为增加 OCR：

导致所有普通 PDF 性能明显下降。

纯文字 PDF：

不应该调用 OCR。

## **四十、严格的阶段边界规则**

除非前一阶段存在阻塞性 bug，否则：

【不得跨阶段实现功能】

例如：

阶段 3：

不能顺手完成 TableRecognizer。

阶段 4：

不能顺手完成 SemanticChunker。

阶段 7：

不能顺手重写 Milvus。

阶段 12：

不能修改 FieldExtractorService 的业务逻辑。

这样避免一次性大重构造成不可控风险。

## **四十一、每阶段必须提供“阶段产物”**

每个阶段至少输出一个可验证产物：

阶段 1：

PageProfile JSON

阶段 2：

PageDocument

阶段 3：

TextBlock JSON

阶段 4：

OcrResult JSON

阶段 5：

BoundingBox / Coordinate Mapping

阶段 6：

Fusion Result

阶段 7：

TableGrid JSON

阶段 8：

TableCell JSON

阶段 9：

DocumentAst JSON

阶段 10：

Cleaned AST

阶段 11：

Markdown

阶段 12：

Chunk JSON

阶段 13：

Milvus Chunk

阶段 14：

ExtractionResult

阶段 15：

测试报告

## **四十二、阶段之间的接口冻结规则**

每个阶段完成后：

核心接口必须尽量冻结。

例如阶段 4 完成后：

OcrParser

不要在阶段 10 随意改变接口。

阶段 9 完成：

DocumentAst

作为后续标准契约。

阶段 12 完成：

RagChunk

作为向量层契约。

除非发现设计性缺陷，否则后续阶段只能扩展，不应反复推翻前面接口。

## **四十三、每阶段 Git/版本边界**

如果当前环境使用 Git：

建议每个阶段形成独立 commit。

例如：

feat(parser): add pdf content detector

feat(parser): add page parser router

feat(parser): extract pdf text positions

feat(ocr): integrate tesseract ocrmypdf

feat(parser): add coordinate fusion

feat(parser): add table structure recovery

feat(parser): add document ast

feat(parser): add document cleaner

feat(rag): add hybrid semantic chunker

feat(rag): integrate structured parser

test(parser): add pdf regression tests

禁止将所有修改压缩成一个巨型 commit。

## **四十四、阶段失败规则**

如果某一阶段失败：

​ \1. 不得继续大规模进入下一阶段

​ \2. 优先修复当前阶段

​ \3. 不得临时绕过核心架构

​ \4. 不得使用硬编码样例数据

​ \5. 不得为了通过测试删除测试

特别是：

《职教园一期施工许可证.pdf》

只能作为：

真实测试样本

不能作为：

特殊硬编码 Case。

## **四十五、最终完成定义**

整个任务完成必须同时满足：

### **解析**

支持：

TEXT_ONLY

IMAGE_ONLY

MIXED

### **OCR**

使用：

OCRmyPDF + Tesseract

中文：

chi_sim

### **混合解析**

支持：

PDF Text

+

Image

+

OCR

+

Coordinate Fusion

### **表格**

支持：

TableGrid

TableRow

TableCell

rowSpan

colSpan

### **结构**

统一：

DocumentAst

### **清洗**

AST 级清洗。

### **输出**

Markdown。

### **RAG**

Hybrid Semantic Chunking。

### **向量**

继续：

DashScope text-embedding-v2

+

Milvus

### **AI 填报**

现有：

FieldExtractorService

功能不被破坏。

### **资源**

支持：

2 CPU

8GB RAM

无 GPU

### **回归**

Excel / Word / PDF

全部通过。

最终形成：

PDF

↓

Detect

↓

Page Router

↓

PDFBox / Tesseract / Image Analysis

↓

Coordinate Fusion

↓

Structure

↓

Document AST

↓

Clean

↓

Markdown

↓

Hybrid Chunk

↓

Embedding

↓

Milvus

↓

AI Extraction

所有阶段必须可独立验证、可回滚、可定位问题。

 

 