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
 * 跨项目字段比较结果 VO（Phase 10 Comparison / Ranking / Aggregation）
 * <p>
 * 计算唯一性（追加约束 6）：rank/aggregates/diffFromCurrent 全部由后端
 * FieldComparisonCalculator 以 BigDecimal 生成（追加约束 7，禁止 double/float），
 * LLM 在最终回答中<b>只能引用</b>这些已算结果，禁止自行计算/换算/派生（含百分比）。
 * <p>
 * 排除可解释（追加约束 5）：被排除单元（冲突/值不可解析/单位不兼容）不参与
 * 排名与聚合，但必须逐条进入 {@link #excluded}（含全部来源值），禁止静默丢弃。
 * <p>
 * 实例独立（追加约束 4）：{@link Unit} 即事实单元 (projectId, projectFormId, fieldCode)
 * 的计算投影，不同表单实例不合并。
 *
 * @author Tang_tzb
 */
@Data
public class CrossProjectComparisonVO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 字段编码
     */
    private String fieldCode;

    /**
     * 字段名称（定义快照）
     */
    private String fieldName;

    /**
     * 字段类型（FieldType 枚举 code；仅 INTEGER/DECIMAL 参与数值计算，追加约束 3）
     */
    private String fieldType;

    /**
     * 基准单位（第一个候选参与单元的 unit，id 升序；unit 不一致单元进入 excluded，追加约束 8）
     */
    private String unit;

    /**
     * 当前项目 ID
     */
    @JsonSerialize(using = ToStringSerializer.class)
    private Long currentProjectId;

    /**
     * 参与本次比较的目标项目 ID 集合（后端映射 + 权限校验后的范围，追加约束 9）
     */
    @JsonSerialize(contentUsing = ToStringSerializer.class)
    private List<Long> targetProjectIds = new ArrayList<>();

    /**
     * 参与单元（数值字段按 normalizedValue 降序含 rank；非数值字段按 id 升序仅列值）
     */
    private List<Unit> units = new ArrayList<>();

    /**
     * 聚合结果（仅数值字段且存在参与单元时非 null；avg 固定 HALF_UP + scale=4，追加约束 7）
     */
    private Aggregates aggregates;

    /**
     * 被排除单元明细（冲突/值不可解析/单位不兼容，禁止静默丢弃，追加约束 5/8）
     */
    private List<ExcludedUnit> excluded = new ArrayList<>();

    /**
     * 参与单元维度（rank 列的行投影）
     */
    @Data
    public static class Unit implements Serializable {

        @Serial
        private static final long serialVersionUID = 1L;

        /**
         * 项目 ID
         */
        @JsonSerialize(using = ToStringSerializer.class)
        private Long projectId;

        /**
         * 项目名称
         */
        private String projectName;

        /**
         * 项目表单实例 ID（事实边界，追加约束 4）
         */
        @JsonSerialize(using = ToStringSerializer.class)
        private Long projectFormId;

        /**
         * 原始展示值（代表行 rawValue；展示优先 rawValue + unit）
         */
        private String rawValue;

        /**
         * 标准化计算值（计算唯一依据，追加约束 6/7）
         */
        private String normalizedValue;

        /**
         * 单位
         */
        private String unit;

        /**
         * 名次（BigDecimal 降序，同值并列 1,2,2,4；仅数值字段非 null）
         */
        private Integer rank;

        /**
         * 与当前项目参与单元的差值（unit.value − current.value；仅当前项目恰 1 个
         * 参与单元时计算，其余为 null，禁止 LLM 自算，追加约束 6）
         */
        private BigDecimal diffFromCurrent;

        /**
         * 差值百分比（diff / |current|，scale=4 HALF_UP；current=0 时为 null）
         */
        private BigDecimal diffFromCurrentPercent;

        /**
         * 来源文件 ID（可空）
         */
        @JsonSerialize(using = ToStringSerializer.class)
        private Long sourceFileId;

        /**
         * 来源文件名（可空）
         */
        private String sourceFileName;

        /**
         * 来源页码（可空）
         */
        private Integer sourcePage;

        /**
         * 来源 ChunkID（可空，硬约束 ⑦：字段值尽量保存来源）
         */
        private String sourceChunkId;
    }

    /**
     * 聚合结果（仅参与单元参与计算，追加约束 6/7）
     */
    @Data
    public static class Aggregates implements Serializable {

        @Serial
        private static final long serialVersionUID = 1L;

        private BigDecimal max;

        private BigDecimal min;

        private BigDecimal sum;

        /**
         * 平均值（HALF_UP + scale=4，追加约束 7）
         */
        private BigDecimal avg;

        /**
         * 参与单元数量
         */
        private Integer count;
    }

    /**
     * 被排除单元（追加约束 5：可解释、禁止静默丢弃）
     */
    @Data
    public static class ExcludedUnit implements Serializable {

        @Serial
        private static final long serialVersionUID = 1L;

        /**
         * 排除原因：CONFLICT（冲突）/ UNPARSEABLE（值不可解析）/ UNIT_INCOMPATIBLE（单位不兼容）
         */
        private String reason;

        /**
         * 项目 ID
         */
        @JsonSerialize(using = ToStringSerializer.class)
        private Long projectId;

        /**
         * 项目名称
         */
        private String projectName;

        /**
         * 项目表单实例 ID
         */
        @JsonSerialize(using = ToStringSerializer.class)
        private Long projectFormId;

        /**
         * 字段编码
         */
        private String fieldCode;

        /**
         * 全部来源值（conflict=true 时完整保留，追加约束 5）
         */
        private List<ProjectStructuredFactsVO.ValueItem> values = new ArrayList<>();
    }
}
