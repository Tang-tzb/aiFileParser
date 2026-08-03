package com.aifp.aiagent.entity.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;
import lombok.AllArgsConstructor;
import lombok.Getter;

import java.util.Set;

/**
 * 文件类型枚举
 * <p>
 * 用于 {@code file_record.file_type} 列，决定阶段 4 调用哪个 Parser。
 * 每个类型关联其合法扩展名集合，供上传时扩展名校验。
 *
 * @author aiFileParser
 */
@Getter
@AllArgsConstructor
public enum FileType {

    PDF("PDF", "PDF文档", Set.of("pdf")),
    EXCEL("EXCEL", "Excel表格", Set.of("xlsx", "xls")),
    WORD("WORD", "Word文档", Set.of("docx", "doc")),
    TXT("TXT", "txt文件", Set.of("txt")),
    OTHER("OTHER", "其他类型", Set.of());

    /**
     * 持久化到 DB file_type 列的值
     */
    @EnumValue
    private final String code;

    /**
     * 中文展示标签
     */
    private final String label;

    /**
     * 合法扩展名集合（小写，不含点）
     */
    private final Set<String> extensions;

    /**
     * 根据扩展名（小写、不含点）解析为 FileType，未匹配返回 null。
     *
     * @param extension 扩展名
     * @return 文件类型，不支持时 null
     */
    public static FileType ofExtension(String extension) {
        if (extension == null || extension.isBlank()) {
            return null;
        }
        for (FileType type : values()) {
            if (type.extensions.contains(extension.toLowerCase())) {
                return type;
            }
        }
        return null;
    }
}
