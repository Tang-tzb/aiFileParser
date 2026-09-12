package com.aifp.aiagent.rag.impl;

import com.aifp.aiagent.common.ResultCode;
import com.aifp.aiagent.document.ParserDocument;
import com.aifp.aiagent.dto.FileRecordVO;
import com.aifp.aiagent.entity.enums.FileStatus;
import com.aifp.aiagent.exception.BusinessException;
import com.aifp.aiagent.parser.FileParser;
import com.aifp.aiagent.parser.FileParserRegistry;
import com.aifp.aiagent.parser.StructuredFileParser;
import com.aifp.aiagent.parser.pdf.ast.DocumentAst;
import com.aifp.aiagent.parser.pdf.chunk.HybridSemanticChunker;
import com.aifp.aiagent.rag.*;
import com.aifp.aiagent.service.FileService;
import com.aifp.aiagent.service.storage.FileStorageService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.IOException;
import java.util.List;

/**
 * DocumentIngestionService 实现
 * <p>
 * 编排顺序：getById → PARSING → parse+chunk → VECTORING → store。
 * 阶段 13 严格状态序列：ingest 完成停在 VECTORING（不落 SUCCESS），
 * SUCCESS 仅由抽取阶段（FieldExtractorService）落库，
 * 保证 UPLOADED → PARSING → VECTORING → EXTRACTING → SUCCESS / FAILED。
 * 切片链路分派（阶段 12 链路替换）：实现 {@link StructuredFileParser} 的解析器
 * （PDF）走 {@code DocumentAst → HybridSemanticChunker} 混合语义切片；
 * 其余类型走全文 {@code ParserDocument → DocumentChunker} 旧滑窗切片。
 * 任一异常 → updateStatus(FAILED) 并抛出对应业务异常。
 *
 * @author Tang_tzb
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DocumentIngestionServiceImpl implements DocumentIngestionService {

    private final FileService fileService;
    private final FileStorageService fileStorageService;
    private final FileParserRegistry fileParserRegistry;
    private final DocumentChunker documentChunker;
    private final VectorStoreService vectorStoreService;
    private final HybridSemanticChunker hybridSemanticChunker;
    private final ChunkVectorConverter chunkVectorConverter;

    @Override
    public void ingest(Long fileId) {
        ingest(fileId, null);
    }

    @Override
    public void ingest(Long fileId, ProgressCallback callback) {
        FileRecordVO record = fileService.getById(fileId);
        // 幂等（阶段 13）：已向量化入库即跳过——SUCCESS（全流程完成）/ VECTORING（已入库待抽取）
        // / EXTRACTING（抽取中或崩溃滞留，chunk 已入库）。避免重复 chunk 写入 Milvus；
        // 跳过时不触发回调（已入库任务无入库进度可报）。
        if (record.getStatus() == FileStatus.SUCCESS
                || record.getStatus() == FileStatus.VECTORING
                || record.getStatus() == FileStatus.EXTRACTING) {
            log.info("文件已入库（{}），跳过 fileId={}", record.getStatus(), fileId);
            return;
        }
        try {
            doIngest(record, callback);
        } catch (BusinessException e) {
            markFailed(fileId);
            throw e;
        } catch (Exception e) {
            markFailed(fileId);
            throw new BusinessException(ResultCode.FILE_PARSE_ERROR, "文件入库失败: " + fileId, e);
        }
    }

    /**
     * 实际入库流程：状态流转 PARSING → VECTORING，阶段起始触发回调。
     * 阶段 13：不落 SUCCESS——向量化完成即止，SUCCESS 由抽取阶段落库（严格状态序列）。
     * Phase 5：projectId 取自 FileRecord（Phase 2 绑定为权威归属）注入切片 metadata，
     * null（历史文件）时产物与历史 chunk 一致（省略键，§三十一 历史兼容）。
     */
    private void doIngest(FileRecordVO record, ProgressCallback callback) {
        Long fileId = record.getFileId();
        Long projectId = record.getProjectId();
        fileService.updateStatus(fileId, FileStatus.PARSING);
        notifyStage(callback, "PARSING");
        List<Document> chunks = parseAndChunk(record, fileId, projectId);

        fileService.updateStatus(fileId, FileStatus.VECTORING);
        notifyStage(callback, "VECTORING");
        vectorStoreService.store(chunks);
        log.info("文件向量化完成 fileId={}, projectId={}, chunks={}", fileId, projectId, chunks.size());
    }

    /**
     * 解析并切片（阶段 12 链路替换）：实现 {@link StructuredFileParser} 的解析器
     * 走 {@code DocumentAst → HybridSemanticChunker → Document} 结构链路；
     * 其余类型走全文 {@code ParserDocument → DocumentChunker} 旧滑窗链路，
     * fileId/projectId 由本服务注入供 chunk 元数据按文件/项目过滤（projectId 为 Phase 5
     * 写入侧注入，null 历史兼容省略键；检索 filter 本阶段不变）。
     */
    private List<Document> parseAndChunk(FileRecordVO record, Long fileId, Long projectId) {
        Resource resource = fileStorageService.load(record.getFilePath());
        File file = toFile(resource, record.getFileName());
        FileParser parser = fileParserRegistry.get(record.getFileType());
        if (parser instanceof StructuredFileParser structured) {
            DocumentAst ast = structured.parseStructured(file);
            return chunkVectorConverter.convert(hybridSemanticChunker.chunk(ast, fileId), projectId);
        }
        ParserDocument doc = parser.parse(file);
        doc.getMetadata().setFileId(fileId);
        doc.getMetadata().setProjectId(projectId);
        return documentChunker.chunk(doc);
    }

    /**
     * 阶段起始回调，null 安全。
     */
    private void notifyStage(ProgressCallback callback, String stageCode) {
        if (callback != null) {
            callback.onStageStart(stageCode);
        }
    }

    /**
     * Resource → java.io.File（FileSystemResource 支持 getFile）。
     */
    private File toFile(Resource resource, String fileName) {
        try {
            return resource.getFile();
        } catch (IOException e) {
            log.error("读取文件失败: {}", fileName, e);
            throw new BusinessException(ResultCode.FILE_NOT_FOUND, "文件不存在: " + fileName);
        }
    }

    /**
     * 标记文件为失败状态，吞掉二次异常避免覆盖原始异常。
     */
    private void markFailed(Long fileId) {
        try {
            fileService.updateStatus(fileId, FileStatus.FAILED);
        } catch (Exception ex) {
            log.warn("标记失败状态异常 fileId={}", fileId, ex);
        }
    }
}
