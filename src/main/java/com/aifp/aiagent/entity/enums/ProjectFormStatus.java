package com.aifp.aiagent.entity.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;
import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 项目表单实例状态枚举
 * <p>
 * 用于 {@code project_form.status} 列。ACTIVE（有效，默认态）与
 * ARCHIVED（归档，只读保留）；归档后不再参与抽取结果写入。
 *
 * @author Tang_tzb
 */
@Getter
@AllArgsConstructor
public enum ProjectFormStatus {

    ACTIVE("ACTIVE", "有效"),
    ARCHIVED("ARCHIVED", "归档");

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
