package com.aifp.aiagent.parser.pdf;

import com.aifp.aiagent.parser.pdf.ast.DocumentAst;

import java.io.File;

/**
 * 文档解析器（阶段 9，对应《PDF解析改造方案》§二十五）。
 * <p>
 * 完整文档解析统一入口：内部编排 类型检测 → 页面路由 → 页面解析 → AST 组装，
 * 产出 {@link DocumentAst}——后续 Cleaner（阶段 10）/ Markdown（阶段 11）/
 * Chunker（阶段 12）的唯一标准结构源（约束 3）。
 * <p>
 * 与 {@link com.aifp.aiagent.parser.FileParser}（FileType 策略契约）相互独立：
 * 生产链路接线（ParserDocument.ast）留待阶段 10/11，本阶段独立交付。
 *
 * @author Tang_tzb
 */
public interface DocumentParser {

    /**
     * 解析整个文档为统一 AST。
     *
     * @param file 源文件
     * @return 文档 AST
     */
    DocumentAst parse(File file);
}
