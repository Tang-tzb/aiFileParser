package com.aifp.aiagent.entity.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;
import lombok.AllArgsConstructor;
import lombok.Getter;

import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;
import java.util.Set;

/**
 * 文件处理状态枚举
 * <p>
 * 用于 {@code file_record.status} 列，表示文件在解析流水线中的阶段。
 * 流转：UPLOADED → PARSING → VECTORING → EXTRACTING → SUCCESS / FAILED。
 * <p>
 * 阶段 13 状态机契约：合法迁移由 {@link #canTransitionTo} 白名单约束，
 * {@code FileService.updateStatus} 为状态唯一写入口并强制校验；
 * SUCCESS 为终态（无出边），FAILED 仅可经重试回到 PARSING；
 * 同态写（from == to）按幂等 no-op 处理，不经本表（见 FileServiceImpl）。
 *
 * @author Tang_tzb
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
     * 状态机合法迁移白名单（from → 允许的 to 集合），静态初始化后不可变
     */
    private static final Map<FileStatus, Set<FileStatus>> LEGAL_TRANSITIONS;

    static {
        Map<FileStatus, Set<FileStatus>> transitions = new EnumMap<>(FileStatus.class);
        transitions.put(UPLOADED, Set.of(PARSING));
        transitions.put(PARSING, Set.of(VECTORING, FAILED));
        transitions.put(VECTORING, Set.of(EXTRACTING, FAILED));
        transitions.put(EXTRACTING, Set.of(SUCCESS, FAILED));
        // 重试：FAILED 重新入库，从 PARSING 起步
        transitions.put(FAILED, Set.of(PARSING));
        // 终态封闭：SUCCESS 无任何出边（阶段 13 防 SUCCESS → EXTRACTING 倒退）
        transitions.put(SUCCESS, Set.of());
        LEGAL_TRANSITIONS = Collections.unmodifiableMap(transitions);
    }

    /**
     * 持久化到 DB status 列的值
     */
    @EnumValue
    private final String code;

    /**
     * 中文展示标签
     */
    private final String label;

    /**
     * 判断本状态是否允许迁移到目标状态（状态机白名单校验）。
     * 同态迁移（this == target）不在本表判断范围，由调用方按幂等 no-op 处理。
     *
     * @param target 目标状态
     * @return true 表示迁移合法
     */
    public boolean canTransitionTo(FileStatus target) {
        if (target == null || target == this) {
            return false;
        }
        return LEGAL_TRANSITIONS.get(this).contains(target);
    }
}
