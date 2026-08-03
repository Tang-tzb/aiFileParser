package com.aifp.aiagent.entity.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;
import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 文件处理状态枚举
 * <p>
 * 用于 {@code file_record.status} 列，表示文件在解析流水线中的阶段。
 * 流转：UPLOADED → PARSING → VECTORING → EXTRACTING → SUCCESS / FAILED。
 * 本阶段（阶段 3）仅使用 UPLOADED，其余状态供阶段 4/5/6 调用 updateStatus 流转。
 *
 * @author aiFileParser
 */
@Getter
@AllArgsConstructor
public enum FileStatus {

    UPLOADED("UPLOADED", "已上传"),
    PARSING("PARSING", "解析中"),
    VECTORING("VECTORING", "向量化中"),
    EXTRACTING("EXTRACTING", "字段抽取中"),
    SUCCESS("SUCCESS", "处理成功"),
    FAILED("FAILED", "处理失败");

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
