package com.aifp.aiagent.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 实体基类
 * <p>
 * 统一主键策略（雪花算法 assign_id）、审计时间与逻辑删除字段。
 * 子类继承即可，无需重复声明。
 *
 * @author aiFileParser
 */
@Data
public abstract class BaseEntity implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 主键ID，雪花算法生成
     */
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /**
     * 创建时间：插入时由 MetaObjectHandler 自动填充
     */
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    /**
     * 更新时间：插入与更新时自动填充
     */
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;

    /**
     * 逻辑删除标记：0 正常 1 删除（全局配置 logic-delete-field=deleted）
     */
    @TableLogic
    private Integer deleted;
}
