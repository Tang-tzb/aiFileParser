package com.aifp.aiagent.parser;

import com.aifp.aiagent.parser.pdf.ast.DocumentAst;

import java.io.File;

/**
 * 结构化文件解析器接口（阶段 12）：解析文件为清洗后的
 * {@link DocumentAst}，供混合语义切片链路消费。
 * <p>
 * 与 {@link FileParser#parse(File)}（全文 ParserDocument 契约，服务
 * 旧 DocumentChunker 滑窗链路）并行：实现类按能力选择性实现本接口，
 * 入库链路经 {@code instanceof} 分派（结构链路优先）。
 *
 * @author Tang_tzb
 */
public interface StructuredFileParser {

    /**
     * 解析文件为清洗后 DocumentAst（结构链路：解析 → 阶段 10 清洗流水线）。
     *
     * @param file 待解析文件
     * @return 清洗后文档 AST
     */
    DocumentAst parseStructured(File file);
}
