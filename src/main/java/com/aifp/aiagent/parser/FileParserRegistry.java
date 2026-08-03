package com.aifp.aiagent.parser;

import com.aifp.aiagent.common.ResultCode;
import com.aifp.aiagent.entity.enums.FileType;
import com.aifp.aiagent.exception.BusinessException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 文件解析器策略注册表
 * <p>
 * Spring 注入所有 {@link FileParser} bean，按 {@link FileParser#supportedType()}
 * 构建 {@code FileType → FileParser} 映射，{@link #get(FileType)} 自动选择对应解析器。
 * 未注册类型抛 {@link ResultCode#FILE_TYPE_NOT_SUPPORT}。
 *
 * @author aiFileParser
 */
@Slf4j
@Component
public class FileParserRegistry {

    private final Map<FileType, FileParser> parsers;

    public FileParserRegistry(List<FileParser> parserList) {
        this.parsers = parserList.stream()
                .collect(Collectors.toMap(FileParser::supportedType, Function.identity()));
        log.info("文件解析器注册完成: {}", parsers.keySet());
    }

    /**
     * 按文件类型获取解析器。
     *
     * @param type 文件类型
     * @return 对应解析器
     * @throws BusinessException 无可用解析器时抛 FILE_TYPE_NOT_SUPPORT
     */
    public FileParser get(FileType type) {
        FileParser parser = parsers.get(type);
        if (parser == null) {
            throw new BusinessException(ResultCode.FILE_TYPE_NOT_SUPPORT,
                    "无可用解析器: " + type);
        }
        return parser;
    }
}
