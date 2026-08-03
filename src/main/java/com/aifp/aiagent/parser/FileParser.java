package com.aifp.aiagent.parser;

import com.aifp.aiagent.document.ParserDocument;
import com.aifp.aiagent.entity.enums.FileType;

import java.io.File;

/**
 * 文件解析器统一接口
 * <p>
 * 每个实现对应一种 {@link FileType}，由 {@link FileParserRegistry} 按
 * {@link #supportedType()} 自动选择（策略模式）。
 *
 * @author aiFileParser
 */
public interface FileParser {

    /**
     * 该解析器支持的文件类型（用于策略注册与自动选择）。
     *
     * @return 文件类型
     */
    FileType supportedType();

    /**
     * 解析文件为统一 {@link ParserDocument} 模型。
     *
     * @param file 待解析文件
     * @return 解析结果（含内容与元数据）
     */
    ParserDocument parse(File file);
}
