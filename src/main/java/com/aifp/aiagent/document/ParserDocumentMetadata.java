package com.aifp.aiagent.document;

import com.aifp.aiagent.entity.enums.FileType;
import lombok.Builder;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * 解析文档元数据
 * <p>
 * {@code page} 语义按文件类型差异化（文档化约定）：
 * <ul>
 *   <li>PDF：页数</li>
 *   <li>Excel：工作表数</li>
 *   <li>Word：段落数</li>
 * </ul>
 *
 * @author aiFileParser
 */
@Data
@Builder
public class ParserDocumentMetadata implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 原始文件名
     */
    private String fileName;

    /**
     * 结构页数：PDF=页数 / Excel=工作表数 / Word=段落数
     */
    private Integer page;

    /**
     * 文件类型
     */
    private FileType type;
}
