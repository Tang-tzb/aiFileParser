package com.aifp.aiagent.service;

import com.aifp.aiagent.entity.ProjectFormFieldValue;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Objects;

/**
 * 字段值冲突判定器（需求 §十九 点名组件，Phase 7 读取侧）
 * <p>
 * <b>纯函数</b>：不做任何数据库/外部服务访问，输入即真相（约束 2）。
 * 调用方（ProjectQueryService）负责只传入"未逻辑删除 + ACTIVE ProjectForm"的值行——
 * 逻辑删除行由 MyBatis-Plus {@code @TableLogic} 在查询侧自动过滤，
 * 非 ACTIVE 表单的行由 Service 分组前排除；本组件不再重复查询或过滤数据库。
 * <p>
 * 判定规则（约束 4）：同一 {@code (projectFormId, fieldCode)} 分组内，
 * 非 null {@code normalizedValue} 的 distinct 数 &gt; 1 即冲突。
 * 字符串相等仅为本阶段比较语义，不代表业务绝对等价（单位/币种换算属后续标准化策略）；
 * {@code normalizedValue} 为 null 的行无法参与比较，不计入冲突判定（仍随结果完整返回）。
 * <p>
 * 不裁决（约束 5）：ProjectForm.version 不参与判定，本组件只报告冲突，
 * 不选择、不覆盖任何值；优先级策略属 Phase 10。
 *
 * @author Tang_tzb
 */
@Component
public class FieldValueConflictResolver {

    /**
     * 判定同一字段分组内的值是否冲突。
     *
     * @param rows 同一 {@code (projectFormId, fieldCode)} 的有效值行
     *             （调用方保证：未逻辑删除 + 所属 ProjectForm 为 ACTIVE）
     * @return true：distinct 非 null normalizedValue 数 &gt; 1
     */
    public boolean hasConflict(List<ProjectFormFieldValue> rows) {
        if (rows == null || rows.size() <= 1) {
            return false;
        }
        long distinctNormalizedValues = rows.stream()
                .filter(Objects::nonNull)
                .map(ProjectFormFieldValue::getNormalizedValue)
                .filter(Objects::nonNull)
                .distinct()
                .count();
        return distinctNormalizedValues > 1;
    }
}
