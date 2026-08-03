package com.aifp.aiagent.service.storage;

import com.aifp.aiagent.common.ResultCode;
import com.aifp.aiagent.entity.enums.FileType;
import com.aifp.aiagent.exception.BusinessException;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.UUID;

/**
 * 本地磁盘文件存储实现
 * <p>
 * 存储规则：{upload-dir}/yyyy/MM/{uuid}.{ext}
 * - 年月子目录避免单目录文件过多；
 * - UUID 文件名避免冲突与中文路径问题。
 *
 * @author aiFileParser
 */
@Slf4j
@Service
public class LocalFileStorageService implements FileStorageService {

    private static final DateTimeFormatter MONTH_DIR = DateTimeFormatter.ofPattern("yyyy/MM");

    @Value("${file.upload-dir}")
    private String uploadDir;

    private Path rootPath;

    @PostConstruct
    void init() {
        this.rootPath = Paths.get(uploadDir).toAbsolutePath().normalize();
        try {
            Files.createDirectories(rootPath);
            log.info("文件存储根目录初始化: {}", rootPath);
        } catch (IOException e) {
            throw new IllegalStateException("无法创建上传目录: " + rootPath, e);
        }
    }

    @Override
    public String store(MultipartFile file, FileType fileType) {
        String original = file.getOriginalFilename();
        String ext = extractExtension(original);
        String relativePath = LocalDate.now().format(MONTH_DIR)
                + "/" + UUID.randomUUID().toString().replace("-", "")
                + (ext.isEmpty() ? "" : "." + ext);

        Path target = rootPath.resolve(relativePath).normalize();
        try {
            Files.createDirectories(target.getParent());
            Files.copy(file.getInputStream(), target);
            log.info("文件已存储: {} ({} bytes) -> {}", original, file.getSize(), target);
            return relativePath;
        } catch (IOException e) {
            log.error("文件存储失败: {}", original, e);
            throw new BusinessException(ResultCode.FILE_UPLOAD_ERROR, "文件保存失败: " + original);
        }
    }

    @Override
    public Resource load(String relativePath) {
        Path target = rootPath.resolve(relativePath).normalize();
        return new FileSystemResource(target);
    }

    @Override
    public void delete(String relativePath) {
        Path target = rootPath.resolve(relativePath).normalize();
        try {
            Files.deleteIfExists(target);
        } catch (IOException e) {
            log.warn("文件删除失败: {}", target, e);
        }
    }

    /**
     * 提取扩展名（小写、不含点），无扩展名返回空串
     */
    private String extractExtension(String fileName) {
        if (fileName == null) {
            return "";
        }
        int dot = fileName.lastIndexOf('.');
        if (dot < 0 || dot == fileName.length() - 1) {
            return "";
        }
        return fileName.substring(dot + 1).toLowerCase();
    }
}
