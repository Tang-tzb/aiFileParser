package com.aifp.aiagent.dto;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * 项目助手回答证据项（需求 §三十六基础版，扁平单类）
 * <p>
 * 按 {@code type} 区分两组，组内无关字段为 null（避免双层判别式结构的序列化负担）：
 * <ul>
 *   <li>{@code STRUCTURED}：结构化字段值来源（fieldCode/fieldName/rawValue/…）</li>
 *   <li>{@code FILE}：RAG 命中文档切片（fileId/fileName/page/chunkId）</li>
 * </ul>
 * 语义（追加约束 3）：本 VO 是"本次回答可使用的证据集合"成员，不代表 LLM
 * 实际引用；精确 citation 属 Phase 11。
 *
 * @author Tang_tzb
 */
@Data
public class AssistantReferenceVO implements Serializable {

    /**
     * 证据类型：结构化字段值来源
     */
    public static final String TYPE_STRUCTURED = "STRUCTURED";
    /**
     * 证据类型：文档切片
     */
    public static final String TYPE_FILE = "FILE";
    @Serial
    private static final long serialVersionUID = 1L;
    /**
     * 证据类型（STRUCTURED / FILE）
     */
    private String type;

    // ==================== STRUCTURED 组 ====================

    /**
     * 字段编码（定义快照）
     */
    private String fieldCode;

    /**
     * 字段名称（定义快照）
     */
    private String fieldName;

    /**
     * 原始抽取值（用户可读展示值）
     */
    private String rawValue;

    /**
     * 标准化值（后端计算值）
     */
    private String normalizedValue;

    /**
     * 单位
     */
    private String unit;

    /**
     * 来源文件 ID
     */
    @JsonSerialize(using = ToStringSerializer.class)
    private Long sourceFileId;

    /**
     * 来源文件名（文件记录缺失时为 null）
     */
    private String sourceFileName;

    /**
     * 来源页码
     */
    private Integer sourcePage;

    /**
     * 来源 ChunkID
     */
    private String sourceChunkId;

    // ==================== FILE 组 ====================

    /**
     * 文件 ID（来自 RAG 切片 metadata）
     */
    @JsonSerialize(using = ToStringSerializer.class)
    private Long fileId;

    /**
     * 文件名（来自 RAG 切片 metadata）
     */
    private String fileName;

    /**
     * 切片起始页码（pageStart）
     */
    private Integer page;

    /**
     * 切片 ID（Document.id）
     */
    private String chunkId;
}
