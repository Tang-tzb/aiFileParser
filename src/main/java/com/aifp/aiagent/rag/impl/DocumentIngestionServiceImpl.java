package com.aifp.aiagent.rag.impl;

import com.aifp.aiagent.common.ResultCode;
import com.aifp.aiagent.document.ParserDocument;
import com.aifp.aiagent.dto.FileRecordVO;
import com.aifp.aiagent.entity.enums.FileStatus;
import com.aifp.aiagent.exception.BusinessException;
import com.aifp.aiagent.parser.FileParser;
import com.aifp.aiagent.parser.FileParserRegistry;
import com.aifp.aiagent.rag.DocumentChunker;
import com.aifp.aiagent.rag.DocumentIngestionService;
import com.aifp.aiagent.rag.VectorStoreService;
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
 * 编排顺序：getById → PARSING → load → parse → setFileId → VECTORING → chunk → store → SUCCESS。
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

    @Override
    public void ingest(Long fileId) {
        FileRecordVO record = fileService.getById(fileId);
        // 幂等：已成功入库则跳过，避免重复 chunk
        if (record.getStatus() == FileStatus.SUCCESS) {
            log.info("文件已入库，跳过 fileId={}", fileId);
            return;
        }
        try {
            doIngest(record);
        } catch (BusinessException e) {
            markFailed(fileId);
            throw e;
        } catch (Exception e) {
            markFailed(fileId);
            throw new BusinessException(ResultCode.FILE_PARSE_ERROR, "文件入库失败: " + fileId, e);
        }
    }

    /**
     * 实际入库流程：状态流转 PARSING → VECTORING → SUCCESS。
     */
    private void doIngest(FileRecordVO record) {
        Long fileId = record.getFileId();
        fileService.updateStatus(fileId, FileStatus.PARSING);
        ParserDocument doc = parseDocument(record);
        // 由 IngestionService 注入 fileId，供 chunk 元数据按文件过滤
        doc.getMetadata().setFileId(fileId);

        fileService.updateStatus(fileId, FileStatus.VECTORING);
        List<Document> chunks = documentChunker.chunk(doc);
        vectorStoreService.store(chunks);
        fileService.updateStatus(fileId, FileStatus.SUCCESS);
        log.info("文件入库完成 fileId={}, chunks={}", fileId, chunks.size());
    }

    /**
     * 加载文件资源并按类型解析为统一文档模型。
     */
    private ParserDocument parseDocument(FileRecordVO record) {
        Resource resource = fileStorageService.load(record.getFilePath());
        File file = toFile(resource, record.getFileName());
        FileParser parser = fileParserRegistry.get(record.getFileType());
        return parser.parse(file);
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
