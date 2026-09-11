package com.aifp.aiagent.entity.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;
import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 项目状态枚举
 * <p>
 * 用于 {@code project.status} 列。Phase 1 仅两态：
 * ACTIVE（进行中，默认态）与 ARCHIVED（已归档，只读保留）。
 * 后续如需冻结/关闭等状态在此扩展，避免破坏已有数据语义。
 *
 * @author Tang_tzb
 */
@Getter
@AllArgsConstructor
public enum ProjectStatus {

    ACTIVE("ACTIVE", "进行中"),
    ARCHIVED("ARCHIVED", "已归档");

    /**
     * 持久化到 DB status 列的值
     */
    @EnumValue
    private final String code;

    /**
     * 中文展示标签
     */
    private final String label;
}
