package com.aifp.aiagent.document;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * 解析后的统一文档模型
 * <p>
 * 各文件解析器输出该统一模型，供后续阶段（语义分块、向量化、字段抽取）使用。
 *
 * @author aiFileParser
 */
@Data
public class ParserDocument implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 解析出的文本内容（全文）
     */
    private String content;

    /**
     * 文档元数据
     */
    private ParserDocumentMetadata metadata;
}
