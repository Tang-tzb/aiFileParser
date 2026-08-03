package com.aifp.aiagent.service.storage;

import com.aifp.aiagent.entity.enums.FileType;
import org.springframework.core.io.Resource;
import org.springframework.web.multipart.MultipartFile;

/**
 * 文件存储服务
 * <p>
 * 存储层抽象，本地实现见 {@link LocalFileStorageService}。
 * 后续如需 OSS/S3，新增实现类即可，无需改动 {@code FileService}。
 *
 * @author aiFileParser
 */
public interface FileStorageService {

    /**
     * 保存上传文件，返回相对存储路径（相对 upload-dir）。
     *
     * @param file     上传文件
     * @param fileType 文件类型
     * @return 相对路径，如 "2026/07/uuid.pdf"
     */
    String store(MultipartFile file, FileType fileType);

    /**
     * 按相对路径加载文件为 Resource（供后续下载/解析读取）。
     *
     * @param relativePath 相对路径
     * @return 文件 Resource
     */
    Resource load(String relativePath);

    /**
     * 按相对路径删除文件（供后续清理/失败回滚）。
     *
     * @param relativePath 相对路径
     */
    void delete(String relativePath);
}
