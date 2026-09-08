package com.aifp.aiagent.parser.pdf.chunk;

import lombok.Builder;
import lombok.Data;

/**
 * 切片块（阶段 12）：混合语义切片的统一产出模型。
 * <p>
 * 必需字段满足阶段验收点 6（Chunk 带完整 metadata）：
 * fileId / fileName / pageStart / pageEnd / titlePath / chunkType /
 * chunkIndex / totalChunks / content；可选字段（bbox / confidence /
 * sourceType）由块内节点聚合得出，缺失时为 {@code null}（下游转换层
 * 省略对应 metadata 键）。
 * <p>
 * 类型约定：fileId 为 String（Long → String，与"Long 型 ID 序列化为
 * JSON 字符串"硬约束及旧 DocumentChunker 的 Milvus 标量过滤约定一致）。
 *
 * @author Tang_tzb
 */
@Data
@Builder
public class Chunk {

    /**
     * 块文本内容
     */
    private String content;

    /**
     * 文件记录 ID（file_record 主键，字符串化）
     */
    private String fileId;

    /**
     * 源文件名
     */
    private String fileName;

    /**
     * 块覆盖起始页（1-based）
     */
    private Integer pageStart;

    /**
     * 块覆盖结束页（1-based）
     */
    private Integer pageEnd;

    /**
     * 所属标题路径（" / " 连接；无标题文档为空串）
     */
    private String titlePath;

    /**
     * 块类型
     */
    private ChunkType chunkType;

    /**
     * 全局序号（0-based，由 HybridSemanticChunker 统一编号）
     */
    private Integer chunkIndex;

    /**
     * 全局切片总数
     */
    private Integer totalChunks;

    /**
     * 可选：块内节点 bbox 并集（"x,y,width,height"，PDF 用户空间 pt）
     */
    private String bbox;

    /**
     * 可选：块内非空置信度最小值（保守聚合；全空为 null）
     */
    private Float confidence;

    /**
     * 可选：块内数据来源去重逗号连接（如 "PDF_TEXT,OCR"）
     */
    private String sourceType;
}
