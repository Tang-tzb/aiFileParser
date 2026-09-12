package com.aifp.aiagent.service.impl;

import com.aifp.aiagent.assistant.ComparisonSlotExtractor;
import com.aifp.aiagent.assistant.ComparisonSlotExtractor.ComparisonSlots;
import com.aifp.aiagent.assistant.ProjectComparisonService;
import com.aifp.aiagent.dto.CrossProjectComparisonVO;
import com.aifp.aiagent.dto.ProjectFieldDictionaryVO;
import com.aifp.aiagent.dto.ProjectFieldFactVO;
import com.aifp.aiagent.service.FieldComparisonCalculator;
import com.aifp.aiagent.service.ProjectAccessService;
import com.aifp.aiagent.service.ProjectQueryService;
import com.aifp.aiagent.service.ProjectService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * 跨项目比较编排服务实现（Phase 10；设计见 {@link ProjectComparisonService}）。
 * <p>
 * 权限边界（追加约束 9）：候选范围恒来自后端集合
 * {@code ProjectService.listAllProjectIds() + canAccess}；LLM 仅提供项目名候选，
 * 名称→ID 映射在本类完成并受集合约束；字段事实查询内部再逐项目守门（403/6001）。
 *
 * @author Tang_tzb
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ProjectComparisonServiceImpl implements ProjectComparisonService {

    private final ProjectService projectService;
    private final ProjectAccessService projectAccessService;
    private final ProjectQueryService projectQueryService;
    private final ComparisonSlotExtractor slotExtractor;
    private final FieldComparisonCalculator comparisonCalculator;

    @Override
    public ComparisonOutcome compare(Long currentProjectId, String standaloneQuestion) {
        Objects.requireNonNull(currentProjectId, "currentProjectId 不可为空");
        if (standaloneQuestion == null || standaloneQuestion.isBlank()) {
            throw new IllegalArgumentException("standaloneQuestion 不可为空白");
        }
        List<Long> accessibleIds = listAccessibleProjectIds(currentProjectId);
        List<ProjectFieldDictionaryVO> dictionary = projectQueryService.queryFieldDictionary(accessibleIds);
        if (dictionary.isEmpty()) {
            return deterministicFieldUnresolved("可访问项目中暂无可比较的字段数据");
        }
        Map<Long, String> nameById = loadProjectNames(accessibleIds);
        // 第二次 LLM：槽位抽取（ChatModel 异常 → 3001 上抛，由全局异常处理）
        ComparisonSlots slots = slotExtractor.extract(
                standaloneQuestion, dictionary, List.copyOf(nameById.values()));
        ComparisonOutcome fieldOutcome = validateField(slots, dictionary);
        if (fieldOutcome != null) {
            return fieldOutcome;
        }
        ScopeResolution scope = resolveScope(slots, accessibleIds, nameById, currentProjectId);
        if (scope.failed()) {
            log.info("跨项目比较范围未定 projectId={}, reason={}", currentProjectId, scope.error());
            return ComparisonOutcome.fallback(scope.error());
        }
        return computeOutcome(currentProjectId, slots, dictionary, scope.targets());
    }

    // ==================== 内部方法 ====================

    /**
     * 可访问项目集合（追加约束 9）：listAllProjectIds + canAccess 过滤；
     * 当前项目已守门通过，集合缺失时兜底并入（并发删除场景），id 升序输出稳定。
     */
    private List<Long> listAccessibleProjectIds(Long currentProjectId) {
        List<Long> ids = projectService.listAllProjectIds().stream()
                .filter(projectAccessService::canAccess)
                .toList();
        if (!ids.contains(currentProjectId)) {
            ids = new ArrayList<>(ids);
            ids.add(currentProjectId);
            ids = ids.stream().sorted().toList();
        }
        return ids;
    }

    /**
     * 项目名称清单（槽位 Prompt 注入与名称→ID 映射依据）；逐项目 getProjectById
     * 守门复用（403/6001），当前量级可控
     */
    private Map<Long, String> loadProjectNames(List<Long> projectIds) {
        Map<Long, String> nameById = new LinkedHashMap<>();
        for (Long id : projectIds) {
            nameById.put(id, projectService.getProjectById(id).getProjectName());
        }
        return nameById;
    }

    /**
     * 字段槽位校验（追加约束 3/12）：fieldCode 空串（抽取失败/不在字典）→ 确定性
     * 降级；numericOperation=true 且类型非数值 → 确定性"不支持该类计算"。
     */
    private ComparisonOutcome validateField(ComparisonSlots slots,
                                            List<ProjectFieldDictionaryVO> dictionary) {
        if (slots.fieldCode().isEmpty()) {
            return deterministicFieldUnresolved("未能从问题中确定要比较的字段");
        }
        ProjectFieldDictionaryVO item = dictionary.stream()
                .filter(d -> d.getFieldCode().equals(slots.fieldCode()))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("字段字典缺少槽位 fieldCode"));
        if (slots.numericOperation() && !FieldComparisonCalculator.isNumericField(item.getFieldType())) {
            return ComparisonOutcome.fallback("字段「" + item.getFieldName()
                    + "」不是数值型字段，当前不支持排名、求和、平均等数值计算，可直接询问其字段值。");
        }
        return null;
    }

    /**
     * 名称→ID 解析（追加约束 1/2/10）：全量语义 → 全部可访问项目；点名列表逐名称
     * 精确→contains 唯一解析（多命中/未识别即失败，禁止猜项目）；"当前项目/本项目"
     * 语义并入 currentProjectId（用户点名列表未含当前项目时不强行加入）；解析为空
     * （未点名且未引用当前项目）→ 范围未定，禁止退化全量。
     */
    private ScopeResolution resolveScope(ComparisonSlots slots, List<Long> accessibleIds,
                                         Map<Long, String> nameById, Long currentProjectId) {
        if (slots.allProjects()) {
            return ScopeResolution.ok(accessibleIds);
        }
        List<Long> targets = new ArrayList<>();
        for (String name : slots.projectNames()) {
            NameResolve resolved = resolveName(name, nameById);
            if (resolved.ambiguous()) {
                return ScopeResolution.fail("无法唯一确定比较范围：项目名称「" + name
                        + "」匹配到多个项目，请使用更精确的项目名称。");
            }
            if (!resolved.found()) {
                return ScopeResolution.fail("无法确定比较范围：未能识别项目「" + name
                        + "」，请使用系统中实际存在的项目名称。");
            }
            targets.add(resolved.projectId());
        }
        if (slots.referencesCurrent() && !targets.contains(currentProjectId)) {
            targets.add(currentProjectId);
        }
        if (targets.isEmpty()) {
            return ScopeResolution.fail("无法确定比较范围：问题未明确参与比较的项目，"
                    + "请补充项目名称，或使用\"所有项目\"等表述。");
        }
        return ScopeResolution.ok(targets.stream().sorted().toList());
    }

    /**
     * 单名称解析：精确唯一 → contains 唯一；重名/多命中 → ambiguous（追加约束 10）
     */
    private NameResolve resolveName(String name, Map<Long, String> nameById) {
        List<Long> exact = idsMatching(nameById, name::equals);
        if (exact.size() == 1) {
            return NameResolve.found(exact.get(0));
        }
        if (exact.size() > 1) {
            return NameResolve.ambiguousMatch();
        }
        List<Long> contains = idsMatching(nameById, value -> value.contains(name));
        if (contains.size() == 1) {
            return NameResolve.found(contains.get(0));
        }
        if (contains.size() > 1) {
            return NameResolve.ambiguousMatch();
        }
        return NameResolve.notFound();
    }

    private List<Long> idsMatching(Map<Long, String> nameById, java.util.function.Predicate<String> matcher) {
        return nameById.entrySet().stream()
                .filter(entry -> matcher.test(entry.getValue()))
                .map(Map.Entry::getKey)
                .toList();
    }

    /**
     * 字段事实查询 + 确定性计算（追加约束 4/5/6/12）：
     * 全部目标项目无值 → 无据降级；参与单元为空（全冲突/全不可解析/全单位不兼容）
     * → 无据降级；否则返回计算结果。
     */
    private ComparisonOutcome computeOutcome(Long currentProjectId, ComparisonSlots slots,
                                             List<ProjectFieldDictionaryVO> dictionary, List<Long> targets) {
        String fieldLabel = dictionary.stream()
                .filter(d -> d.getFieldCode().equals(slots.fieldCode()))
                .findFirst()
                .map(ProjectFieldDictionaryVO::getFieldName)
                .orElse(slots.fieldCode());
        List<ProjectFieldFactVO> facts = projectQueryService.queryFieldFacts(targets, slots.fieldCode());
        if (facts.stream().allMatch(fact -> fact.getValues().isEmpty())) {
            return ComparisonOutcome.fallback("当前项目资料中没有找到足够依据：参与比较的项目均未提供字段「"
                    + fieldLabel + "」的数据。");
        }
        CrossProjectComparisonVO comparison = comparisonCalculator.compute(
                facts, currentProjectId, slots.fieldCode());
        if (comparison.getUnits().isEmpty()) {
            return ComparisonOutcome.fallback("当前项目资料中没有找到足够依据：字段「" + fieldLabel
                    + "」的取值全部存在冲突、无法解析或单位不兼容，无法完成本次比较计算。");
        }
        return ComparisonOutcome.of(comparison);
    }

    private ComparisonOutcome deterministicFieldUnresolved(String reason) {
        return ComparisonOutcome.fallback("未能确定要比较的字段（" + reason
                + "），请在问题中明确字段名称（如总投资、建筑面积）。");
    }

    /**
     * 范围解析结果：targets 非空成功；error 非空失败（追加约束 2/10/12）
     */
    private record ScopeResolution(List<Long> targets, String error) {

        static ScopeResolution ok(List<Long> targets) {
            return new ScopeResolution(targets, null);
        }

        static ScopeResolution fail(String error) {
            return new ScopeResolution(null, error);
        }

        boolean failed() {
            return error != null;
        }
    }

    /**
     * 名称解析三元结果（found/ambiguous/notFound 互斥）。
     * 静态工厂命名 ambiguousMatch：与组件访问器 {@code ambiguous()} 签名冲突不可共存
     */
    private record NameResolve(Long projectId, boolean ambiguous, boolean found) {

        static NameResolve found(Long projectId) {
            return new NameResolve(projectId, false, true);
        }

        static NameResolve ambiguousMatch() {
            return new NameResolve(null, true, false);
        }

        static NameResolve notFound() {
            return new NameResolve(null, false, false);
        }
    }
}
