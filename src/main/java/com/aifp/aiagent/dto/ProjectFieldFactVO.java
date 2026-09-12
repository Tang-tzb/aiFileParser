package com.aifp.aiagent.dto;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

/**
 * 跨项目字段事实单元 VO（Phase 10 Comparison / Ranking / Aggregation）
 * <p>
 * 事实边界（追加约束 4）：恒为 {@code (projectId, projectFormId, fieldCode)}，
 * 跨项目 fieldCode 相同<b>不得合并</b>不同表单实例；同项目多个 ACTIVE 实例
 * 各自独立输出一个本对象。
 * <p>
 * 冲突语义（追加约束 5）：复用 Phase 7 判定（distinct 非 null normalizedValue &gt; 1）；
 * {@code conflict=true} 时 {@link #values} 完整保留全部来源值，交由
 * FieldComparisonCalculator 排除出数值计算并进入 excluded 明细。
 *
 * @author Tang_tzb
 */
@Data
public class ProjectFieldFactVO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 项目 ID（雪花大整数，序列化为字符串避免前端精度丢失）
     */
    @JsonSerialize(using = ToStringSerializer.class)
    private Long projectId;

    /**
     * 项目名称（跨项目比较展示用）
     */
    private String projectName;

    /**
     * 项目表单实例 ID（事实边界组成部分，追加约束 4）
     */
    @JsonSerialize(using = ToStringSerializer.class)
    private Long projectFormId;

    /**
     * 字段编码
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
     * 是否冲突（distinct 非 null normalizedValue 数 &gt; 1，Phase 7 判定复用）
     */
    private boolean conflict;

    /**
     * 全部来源值（conflict=true 时不得丢弃任何一条，追加约束 5）
     */
    private List<ProjectStructuredFactsVO.ValueItem> values = new ArrayList<>();
}
