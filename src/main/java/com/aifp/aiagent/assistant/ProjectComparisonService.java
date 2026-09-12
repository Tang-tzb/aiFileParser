package com.aifp.aiagent.assistant;

import com.aifp.aiagent.dto.CrossProjectComparisonVO;

/**
 * 跨项目比较编排服务（Phase 10 Comparison / Ranking / Aggregation）
 * <p>
 * 编排流程：可访问项目集合（ProjectService.listAllProjectIds + canAccess，
 * 需求 §三十四/§三十五与追加约束 9——LLM 永不直接产出 projectId）→ 字段字典 →
 * ComparisonSlotExtractor（第二次 LLM 槽位抽取 + 后端成员校验）→ 字段类型
 * 可计算性校验（追加约束 3）→ 项目名称→ID 解析（精确→contains，多命中/
 * 未识别确定性降级，追加约束 10）→ 字段级事实（ProjectQueryService）→
 * FieldComparisonCalculator 确定性计算（追加约束 6/7）。
 * <p>
 * 降级语义（追加约束 2/12）：字段未定/范围未定/范围不唯一/类型不支持数值操作/
 * 无数据/全冲突·全不可解析·全单位不兼容 → {@code deterministicAnswer} 确定性
 * 回答（零后续 LLM 零检索）；槽位抽取 ChatModel 异常 → 3001 上抛。
 * <p>
 * 边界：不直查 Mapper；历史不是事实来源——每次比较都重新执行结构化查询与
 * 后端计算（追加约束 14）。
 *
 * @author Tang_tzb
 */
public interface ProjectComparisonService {

    /**
     * 执行跨项目比较编排。
     *
     * @param currentProjectId   当前项目 ID（调用方已守门 403/6001）
     * @param standaloneQuestion 已消解指代的独立问题（standaloneQuestion，Phase 9 追加约束 2）
     * @return 正常路径 comparison 非空；确定性降级 deterministicAnswer 非空（二者互斥）
     */
    ComparisonOutcome compare(Long currentProjectId, String standaloneQuestion);

    /**
     * 编排结果：comparison 与 deterministicAnswer 二选一非空
     */
    record ComparisonOutcome(CrossProjectComparisonVO comparison, String deterministicAnswer) {

        public static ComparisonOutcome of(CrossProjectComparisonVO comparison) {
            return new ComparisonOutcome(comparison, null);
        }

        public static ComparisonOutcome fallback(String answer) {
            return new ComparisonOutcome(null, answer);
        }
    }
}
