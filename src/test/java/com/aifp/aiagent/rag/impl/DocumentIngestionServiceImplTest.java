package com.aifp.aiagent.rag.impl;

import com.aifp.aiagent.common.ResultCode;
import com.aifp.aiagent.document.ParserDocument;
import com.aifp.aiagent.document.ParserDocumentMetadata;
import com.aifp.aiagent.dto.FileRecordVO;
import com.aifp.aiagent.entity.enums.FileStatus;
import com.aifp.aiagent.entity.enums.FileType;
import com.aifp.aiagent.exception.BusinessException;
import com.aifp.aiagent.parser.FileParser;
import com.aifp.aiagent.parser.FileParserRegistry;
import com.aifp.aiagent.parser.StructuredFileParser;
import com.aifp.aiagent.parser.pdf.ast.DocumentAst;
import com.aifp.aiagent.parser.pdf.chunk.Chunk;
import com.aifp.aiagent.rag.ChunkVectorConverter;
import com.aifp.aiagent.rag.DocumentChunker;
import com.aifp.aiagent.rag.VectorStoreService;
import com.aifp.aiagent.service.FileService;
import com.aifp.aiagent.service.storage.FileStorageService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.document.Document;
import org.springframework.core.io.Resource;

import java.io.File;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * {@link DocumentIngestionServiceImpl} 测试
 * <p>
 * 离线单测：mock 全部依赖，验证入库编排顺序、幂等跳过、异常置 FAILED，
 * 以及阶段 12 切片链路分派（结构化解析器 → HybridSemanticChunker；
 * 普通解析器 → 旧 DocumentChunker 滑窗）。
 *
 * @author Tang_tzb
 */
@ExtendWith(MockitoExtension.class)
class DocumentIngestionServiceImplTest {

    private static final Long FILE_ID = 1785800001L;

    @TempDir
    Path tempDir;

    @Mock
    private FileService fileService;
    @Mock
    private FileStorageService fileStorageService;
    @Mock
    private FileParserRegistry fileParserRegistry;
    @Mock
    private DocumentChunker documentChunker;
    @Mock
    private VectorStoreService vectorStoreService;
    @Mock
    private com.aifp.aiagent.parser.pdf.chunk.HybridSemanticChunker hybridSemanticChunker;
    @Mock
    private ChunkVectorConverter chunkVectorConverter;
    @Mock
    private FileParser fileParser;
    @Mock
    private Resource resource;

    @InjectMocks
    private DocumentIngestionServiceImpl ingestionService;

    @Test
    void ingest_legacyParserFlow_statusSequenceAndStore() throws Exception {
        // 旧全文链路：mock 解析器未实现 StructuredFileParser → DocumentChunker 滑窗
        when(fileService.getById(FILE_ID)).thenReturn(buildRecord(FileStatus.UPLOADED));
        stubParseAndChunk();

        ingestionService.ingest(FILE_ID);

        // 验证状态流转顺序：PARSING → parse → chunk（阶段 12 起切片在 PARSING 段完成）
        // → VECTORING → store；阶段 13 严格序列：ingest 停在 VECTORING，不落 SUCCESS
        var inOrder = inOrder(fileService, fileParserRegistry, fileParser,
                documentChunker, vectorStoreService);
        inOrder.verify(fileService).updateStatus(FILE_ID, FileStatus.PARSING);
        inOrder.verify(fileParserRegistry).get(FileType.PDF);
        inOrder.verify(fileParser).parse(any(File.class));
        inOrder.verify(documentChunker).chunk(any(ParserDocument.class));
        inOrder.verify(fileService).updateStatus(FILE_ID, FileStatus.VECTORING);
        inOrder.verify(vectorStoreService).store(any());
        verify(fileService, never()).updateStatus(eq(FILE_ID), eq(FileStatus.SUCCESS));
    }

    @Test
    void ingest_structuredParserFlow_usesHybridChunker() throws Exception {
        // 阶段 12 链路替换：实现 StructuredFileParser 的解析器 → 混合语义切片
        FileParser structuredAsParser = mock(FileParser.class,
                withSettings().extraInterfaces(StructuredFileParser.class));
        StructuredFileParser structured = (StructuredFileParser) structuredAsParser;
        when(fileService.getById(FILE_ID)).thenReturn(buildRecord(FileStatus.UPLOADED));
        when(fileStorageService.load(any())).thenReturn(resource);
        when(resource.getFile()).thenReturn(tempDir.resolve("a.pdf").toFile());
        when(fileParserRegistry.get(FileType.PDF)).thenReturn(structuredAsParser);
        when(structured.parseStructured(any(File.class)))
                .thenReturn(DocumentAst.builder().fileName("样例.pdf").build());
        when(hybridSemanticChunker.chunk(any(DocumentAst.class), eq(FILE_ID)))
                .thenReturn(List.of(Chunk.builder().content("块内容").build()));
        when(chunkVectorConverter.convert(any()))
                .thenReturn(List.of(new Document("向量化内容")));

        ingestionService.ingest(FILE_ID);

        var inOrder = inOrder(fileService, structured, hybridSemanticChunker,
                chunkVectorConverter, vectorStoreService);
        inOrder.verify(fileService).updateStatus(FILE_ID, FileStatus.PARSING);
        inOrder.verify(structured).parseStructured(any(File.class));
        inOrder.verify(hybridSemanticChunker).chunk(any(DocumentAst.class), eq(FILE_ID));
        inOrder.verify(chunkVectorConverter).convert(any());
        inOrder.verify(fileService).updateStatus(FILE_ID, FileStatus.VECTORING);
        inOrder.verify(vectorStoreService).store(any());
        // 阶段 13：ingest 停在 VECTORING，不落 SUCCESS
        verify(fileService, never()).updateStatus(eq(FILE_ID), eq(FileStatus.SUCCESS));
        // 结构链路不得触碰旧滑窗切片器
        verify(documentChunker, never()).chunk(any(ParserDocument.class));
    }

    @Test
    void ingest_structuredParseFails_markFailedAndRethrow() throws Exception {
        FileParser structuredAsParser = mock(FileParser.class,
                withSettings().extraInterfaces(StructuredFileParser.class));
        StructuredFileParser structured = (StructuredFileParser) structuredAsParser;
        when(fileService.getById(FILE_ID)).thenReturn(buildRecord(FileStatus.UPLOADED));
        when(fileStorageService.load(any())).thenReturn(resource);
        when(resource.getFile()).thenReturn(tempDir.resolve("b.pdf").toFile());
        when(fileParserRegistry.get(FileType.PDF)).thenReturn(structuredAsParser);
        when(structured.parseStructured(any(File.class)))
                .thenThrow(new BusinessException(ResultCode.FILE_PARSE_ERROR, "结构化解析失败"));

        assertThatThrownBy(() -> ingestionService.ingest(FILE_ID))
                .isInstanceOf(BusinessException.class);

        verify(fileService).updateStatus(FILE_ID, FileStatus.FAILED);
        verify(vectorStoreService, never()).store(any());
        verify(fileService, never()).updateStatus(eq(FILE_ID), eq(FileStatus.SUCCESS));
    }

    @Test
    void ingest_alreadySuccess_skipIngest() {
        when(fileService.getById(FILE_ID)).thenReturn(buildRecord(FileStatus.SUCCESS));

        ingestionService.ingest(FILE_ID);

        verify(fileStorageService, never()).load(any());
        verify(documentChunker, never()).chunk(any());
        verify(vectorStoreService, never()).store(any());
        verify(fileService, never()).updateStatus(eq(FILE_ID), any());
    }

    /**
     * 阶段 13 幂等守卫扩展：VECTORING（已入库待抽取）跳过，不重复入库、不写状态。
     */
    @Test
    void ingest_alreadyVectoring_skipIngest() {
        when(fileService.getById(FILE_ID)).thenReturn(buildRecord(FileStatus.VECTORING));

        ingestionService.ingest(FILE_ID);

        verify(fileStorageService, never()).load(any());
        verify(vectorStoreService, never()).store(any());
        verify(fileService, never()).updateStatus(eq(FILE_ID), any());
    }

    /**
     * 阶段 13 幂等守卫扩展：EXTRACTING（抽取中/崩溃滞留，chunk 已入库）跳过，
     * 避免重入库产生重复 chunk。
     */
    @Test
    void ingest_alreadyExtracting_skipIngest() {
        when(fileService.getById(FILE_ID)).thenReturn(buildRecord(FileStatus.EXTRACTING));

        ingestionService.ingest(FILE_ID);

        verify(fileStorageService, never()).load(any());
        verify(vectorStoreService, never()).store(any());
        verify(fileService, never()).updateStatus(eq(FILE_ID), any());
    }

    @Test
    void ingest_parseFails_markFailedAndRethrow() throws Exception {
        when(fileService.getById(FILE_ID)).thenReturn(buildRecord(FileStatus.UPLOADED));
        when(fileStorageService.load(any())).thenReturn(resource);
        when(resource.getFile()).thenReturn(tempDir.resolve("a.pdf").toFile());
        when(fileParserRegistry.get(FileType.PDF)).thenReturn(fileParser);
        when(fileParser.parse(any(File.class)))
                .thenThrow(new BusinessException(ResultCode.FILE_PARSE_ERROR, "解析失败"));

        assertThatThrownBy(() -> ingestionService.ingest(FILE_ID))
                .isInstanceOf(BusinessException.class);

        verify(fileService).updateStatus(FILE_ID, FileStatus.FAILED);
        verify(vectorStoreService, never()).store(any());
        verify(fileService, never()).updateStatus(FILE_ID, FileStatus.SUCCESS);
    }

    // ==================== 测试数据与桩 ====================

    @SuppressWarnings("unchecked")
    private void stubParseAndChunk() throws Exception {
        when(fileStorageService.load(any())).thenReturn(resource);
        when(resource.getFile()).thenReturn(tempDir.resolve("a.pdf").toFile());
        when(fileParserRegistry.get(FileType.PDF)).thenReturn(fileParser);
        when(fileParser.parse(any(File.class))).thenReturn(buildDoc());
        when(documentChunker.chunk(any(ParserDocument.class)))
                .thenReturn(List.<Document>of(new Document("chunk1")));
    }

    private FileRecordVO buildRecord(FileStatus status) {
        FileRecordVO vo = new FileRecordVO();
        vo.setFileId(FILE_ID);
        vo.setFileName("项目申报书.pdf");
        vo.setFileType(FileType.PDF);
        vo.setFilePath("2026/07/abc.pdf");
        vo.setStatus(status);
        return vo;
    }

    private ParserDocument buildDoc() {
        ParserDocument doc = new ParserDocument();
        doc.setContent("项目申报内容");
        doc.setMetadata(ParserDocumentMetadata.builder()
                .fileName("项目申报书.pdf")
                .page(3)
                .type(FileType.PDF)
                .build());
        return doc;
    }
}
