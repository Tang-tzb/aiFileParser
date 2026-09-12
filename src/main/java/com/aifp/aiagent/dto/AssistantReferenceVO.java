package com.aifp.aiagent.dto;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * 项目助手回答证据项（需求 §三十六基础版，扁平单类；Phase 11 增加 citationId 溯源）
 * <p>
 * 按 {@code type} 区分两组，组内无关字段为 null（避免双层判别式结构的序列化负担）：
 * <ul>
 *   <li>{@code STRUCTURED}：结构化字段值来源（fieldCode/fieldName/rawValue/…）</li>
 *   <li>{@code FILE}：RAG 命中文档切片（fileId/fileName/page/chunkId）</li>
 * </ul>
 * 语义（追加约束 3）：本 VO 是"本次回答可使用的证据集合"成员，不代表 LLM
 * 实际引用。Phase 11 溯源：{@code citationId} 为该证据注入 Prompt 时使用的引用
 * 标记（结构化/比较证据 [S{n}]，文档片段 [D{n}]，一次构建内全局唯一），与 Prompt
 * 中的标记、references 顺序严格一一对应；LLM 回答中的标记由
 * AnswerCitationParser 确定性解析映射回本 VO，形成 citations（实际引用子集）。
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

    /**
     * 引用标记（Phase 11）：该证据注入 Prompt 时使用的编号（如 S1/D1），
     * 一次 Prompt 构建内全局唯一（S 与 D 独立递增）；同一证据只登记一次，
     * citations 经该字段从 answer 中确定性映射
     */
    private String citationId;

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
