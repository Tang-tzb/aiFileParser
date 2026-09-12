package com.aifp.aiagent.dto;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * 项目结构化事实聚合 VO（Phase 7 queryProjectFacts 响应体）
 * <p>
 * 事实边界（约束 8）：以 {@code projectFormId + fieldCode} 为最小事实单元，
 * 不同 ProjectForm 的同 fieldCode 字段<b>不合并</b>，交由 Phase 8 QueryPlanner 选择。
 * <p>
 * 冲突语义（约束 3/5）：{@code conflict=true} 时 {@link Field#values} 必须完整保留
 * 全部来源值（rawValue/normalizedValue/unit/sourceFileId/sourceFileName/sourcePage/
 * sourceChunkId/confidence），供 Phase 8 向用户解释"为什么冲突"；本阶段不做任何裁决，
 * version 仅随 Form 透出展示，不参与取值优先级。
 * <p>
 * 来源容错（约束 9）：来源文件记录缺失（已删除等）时 {@code sourceFileName=null}，
 * 其余来源信息照常保留，不影响整体查询成功。
 *
 * @author Tang_tzb
 */
@Data
public class ProjectStructuredFactsVO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 项目 ID（雪花大整数，序列化为字符串避免前端精度丢失）
     */
    @JsonSerialize(using = ToStringSerializer.class)
    private Long projectId;

    /**
     * 项目名称
     */
    private String projectName;

    /**
     * 项目表单实例列表（仅 ACTIVE；无实例时为空列表，非错误）
     */
    private List<Form> forms = new ArrayList<>();

    /**
     * 项目表单实例维度（project_form 一行一个）
     */
    @Data
    public static class Form implements Serializable {

        @Serial
        private static final long serialVersionUID = 1L;

        /**
         * 项目表单实例 ID
         */
        @JsonSerialize(using = ToStringSerializer.class)
        private Long projectFormId;

        /**
         * 表单定义 ID（form_definition）
         */
        @JsonSerialize(using = ToStringSerializer.class)
        private Long formId;

        /**
         * 结构化成功写入代数（仅展示，不参与冲突裁决）
         */
        private Integer version;

        /**
         * 实例状态（恒为 ACTIVE，随查询条件保证）
         */
        private String status;

        /**
         * 字段事实列表（按 fieldCode 分组，同实例内 fieldCode 唯一）
         */
        private List<Field> fields = new ArrayList<>();
    }

    /**
     * 字段维度事实（fieldCode 定义快照 + 冲突标记 + 全部来源值）
     */
    @Data
    public static class Field implements Serializable {

        @Serial
        private static final long serialVersionUID = 1L;

        /**
         * 字段定义 ID（快照归属，可空）
         */
        @JsonSerialize(using = ToStringSerializer.class)
        private Long fieldId;

        /**
         * 字段编码（定义快照）
         */
        private String fieldCode;

        /**
         * 字段名称（定义快照）
         */
        private String fieldName;

        /**
         * 字段类型（FieldType 枚举 code）
         */
        private String fieldType;

        /**
         * 是否冲突（distinct 非 null normalizedValue 数 &gt; 1）
         */
        private boolean conflict;

        /**
         * 全部来源值（conflict=true 时不得丢弃任何一条，约束 3）
         */
        private List<ValueItem> values = new ArrayList<>();
    }

    /**
     * 单条来源值（一条 project_form_field_value 行的可读投影）
     */
    @Data
    public static class ValueItem implements Serializable {

        @Serial
        private static final long serialVersionUID = 1L;

        /**
         * 原始抽取值
         */
        private String rawValue;

        /**
         * 标准化值（本阶段冲突比较依据；字符串相等≠业务绝对等价，约束 4）
         */
        private String normalizedValue;

        /**
         * 单位（如 万元、平方米）
         */
        private String unit;

        /**
         * 来源文件 ID（可空）
         */
        @JsonSerialize(using = ToStringSerializer.class)
        private Long sourceFileId;

        /**
         * 来源文件名（文件记录缺失时为 null，不影响其余字段，约束 9）
         */
        private String sourceFileName;

        /**
         * 来源页码（可空）
         */
        private Integer sourcePage;

        /**
         * 来源 ChunkID（可空）
         */
        private String sourceChunkId;

        /**
         * 抽取置信度（0~1，可空）
         */
        private BigDecimal confidence;
    }
}
