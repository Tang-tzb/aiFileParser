package com.aifp.aiagent.dto;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import lombok.AllArgsConstructor;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * 创建类接口通用返回 VO（雪花 ID 字符串化）
 * <p>
 * 解决 {@code Result<Long>} 裸数字返回导致前端 JS JSON.parse 精度丢失的契约问题：
 * ID 经 {@link ToStringSerializer} 序列化为字符串，前端统一从 {@code data.id} 取值，
 * 禁止直接以 number 承接雪花 ID。
 *
 * @author Tang_tzb
 */
@Data
@AllArgsConstructor
public class IdVO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 雪花 ID（序列化为字符串避免前端 JS 精度丢失）
     */
    @JsonSerialize(using = ToStringSerializer.class)
    private Long id;

    /**
     * 构造单 ID VO（便于 Controller 链式包装）
     */
    public static IdVO of(Long id) {
        return new IdVO(id);
    }
}
