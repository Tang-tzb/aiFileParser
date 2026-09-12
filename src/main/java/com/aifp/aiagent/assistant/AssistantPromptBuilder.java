package com.aifp.aiagent.assistant;

import com.aifp.aiagent.dto.AssistantReferenceVO;
import com.aifp.aiagent.dto.CrossProjectComparisonVO;
import com.aifp.aiagent.dto.ProjectStructuredFactsVO;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.document.Document;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 项目助手回答 Prompt 构建器（需求 §二十二/§二十三；Phase 9 增加对话历史段；
 * Phase 11 增加证据编号登记与引用溯源）
 * <p>
 * System Prompt = 反伪造规则常量（追加约束 1/6/9/12/14 + 硬约束 ⑧ 冲突不裁决 +
 * Phase 9 追加约束 1 历史非事实源 + Phase 11 第 12 条行内引用标记规则）；
 * User Prompt = 按上下文分段渲染（空段整段省略）：对话历史（指代消解语境）/
 * 结构化字段事实（冲突显式标记 + 逐来源明细）/ 项目文件清单 / 文档检索结果
 * （编号与 ExtractionPromptBuilder [C{n}] 同风格，修改编号语义需互相同步）。
 * <p>
 * 职责边界（追加约束 10/12；Phase 11 调整）：渲染与证据登记一体完成——每条注入
 * Prompt 的证据（比较单元/排除值/结构化来源值/文档切片）在渲染时同步登记为
 * {@link AssistantReferenceVO} 并分配 citationId（[S{n}]/[D{n}]），
 * {@link PromptBuildResult#evidence()} 与 userPrompt 中的标记严格一一对应；
 * 不解析业务数字、不组装响应 VO（references/citations/usedFiles 由 ServiceImpl
 * 从 evidence 组装）；回答保持纯自然语言 + 行内引用标记。
 *
 * @author Tang_tzb
 */
@Component
@RequiredArgsConstructor
public class AssistantPromptBuilder {

    /**
     * 文档片段行前缀（%s = citationId，如 D1；编号与 ExtractionPromptBuilder 的
     * [C{n}] 同风格）
     */
    private static final String DOC_MARKER_FORMAT = "[%s] 来源: %s%s%n%s";

    /**
     * 反伪造 System Prompt（§二十三 1-10 条全量；措辞为追加约束 1 原文要求，
     * 修改需同步评估 Prompt 效果与单元测试断言；Phase 10 调整第 8 条归因范围 +
     * 新增第 11 条比较数据唯一来源规则；Phase 11 新增第 12 条行内引用标记规则）
     */
    private static final String SYSTEM_PROMPT = """
            你是项目资料智能助手，负责回答用户关于当前项目的问题。你必须严格遵守以下规则：
            1. 你不能访问数据库、Milvus 或其他外部数据；只能使用本次提供的项目事实和文档片段。
            2. 项目事实（金额、面积、日期、单位、数量等）只能来自本次提供的【项目结构化字段事实】【跨项目比较数据】和【文档检索结果】；无法从本次提供的资料中得到依据的项目事实，必须回答"当前项目资料中没有找到足够依据"，禁止依据自身知识编造或补充。
            3. 不确定或资料不足以完整回答时，必须如实说明，禁止猜测。
            4. 被标记为"冲突"的字段存在多个不同来源值：必须完整列出全部值及其来源文件与页码，禁止选择唯一值，禁止裁决哪个正确。
            5. 展示数值时优先使用 rawValue（用户可读值）+ unit（单位）；涉及比较或计算时只能使用 normalizedValue（后端标准值），禁止自行换算单位或解析业务数字。
            6. 引用文档内容时必须给出文件名和页码，便于用户核对。
            7. 与当前项目明显无关的问题，如实回答"不属于当前项目资料范围"，不要强行作答或编造。
            8. 涉及项目之间归因分析、原因解释的问题（如为什么某项目投资比其他项目高），如实说明当前版本暂不支持此类能力。
            9. 只输出纯自然语言回答，禁止输出 JSON 或其他结构化格式。
            10. 对话历史仅用于理解当前问题的指代关系（如"那、它、这个项目、还有呢"）；历史中提到的项目事实（金额、面积、日期、单位等）不能作为本次回答的依据，即使上一轮已经回答过，本轮涉及项目事实时仍必须以本次提供的【项目结构化字段事实】【跨项目比较数据】和【文档检索结果】为准。
            11. 比较、排名、求和、平均、最大、最小、差值、百分比等跨项目数字结论只能引用【跨项目比较数据】中后端已计算的排名与聚合结果（rank/aggregates/diffFromCurrent/diffFromCurrentPercent），禁止自行计算、换算或派生任何数字（包括百分比）；【文档检索结果】中的数字仅可用于解释性表述，禁止作为比较计算依据；被标记为排除（冲突/值无法解析/单位不兼容/无数据）的实例必须如实说明其未参与本次数值计算。
            12. 回答中引用任何项目事实或文档内容时，必须在该句末尾紧跟对应证据的引用标记：结构化字段值与比较数据使用其行末的 [S编号]，文档片段使用其行首的 [D编号]（如 [S1]、[D2]）；只允许使用本次提供的证据中实际存在的标记，禁止编造编号或使用不存在的标记；引用标记属于普通文本，不属于结构化格式。""";

    private final ChunkMetadataReader chunkMetadataReader;

    /**
     * 构建 Prompt 并登记证据（Phase 11）：渲染与登记一体完成，userPrompt 中出现的
     * 每个 [S{n}]/[D{n}] 标记在 evidence 中存在 citationId 完全一致的成员；
     * 重复 build() 时重新编号，单次构建内编号全局唯一。
     *
     * @param ctx 助手上下文（由 ServiceImpl 按 QueryPlan 组装）
     * @return 用户提示词 + 全量证据（含 citationId）
     */
    public PromptBuildResult build(ProjectAssistantContext ctx) {
        StringBuilder sb = new StringBuilder();
        sb.append("项目：").append(ctx.getProjectName()).append('\n');
        sb.append("用户问题：").append(ctx.getQuestion()).append('\n');
        RenderState state = new RenderState();
        renderHistory(sb, ctx.getHistory());
        renderComparison(sb, ctx.getComparison(), state);
        renderFacts(sb, ctx.getFacts(), state);
        renderFileList(sb, ctx.getFileNames());
        renderDocuments(sb, ctx.getDocuments(), state);
        return new PromptBuildResult(sb.toString(), List.copyOf(state.evidence));
    }

    /**
     * 构建反伪造 System Prompt。
     *
     * @return 系统提示词常量
     */
    public String buildSystemPrompt() {
        return SYSTEM_PROMPT;
    }

    /**
     * 渲染对话历史段（时间顺序，最近一轮在最后，追加约束 3）：
     * 仅用于指代消解语境（追加约束 1，事实依据规则见 System Prompt 第 10 条）；
     * 空历史整段省略。历史中旧引用标记不参与本轮 citations（约束 4：
     * 本轮解析仅针对本轮 evidence，此处不做任何标记处理）。
     */
    private void renderHistory(StringBuilder sb, List<ConversationTurn> history) {
        if (history == null || history.isEmpty()) {
            return;
        }
        sb.append("\n【对话历史】（仅用于理解当前问题的指代关系，不是项目事实来源）\n");
        for (ConversationTurn turn : history) {
            sb.append("用户：").append(turn.getUserQuestion()).append('\n');
            sb.append("助手：").append(turn.getAssistantAnswer()).append('\n');
        }
    }

    // ==================== 内部方法 ====================

    /**
     * 渲染跨项目比较数据段（Phase 10，仅 COMPARISON 意图非 null）：字段维度 +
     * 参与单元（排名/差值由后端算好，LLM 只引用，追加约束 6/11）+ 聚合行 +
     * 排除单元（固定话术明确"未参与本次数值计算"，追加约束 5）；
     * comparison=null 整段省略。
     */
    private void renderComparison(StringBuilder sb, CrossProjectComparisonVO comparison,
                                  RenderState state) {
        if (comparison == null) {
            return;
        }
        sb.append("\n【跨项目比较数据】（以下排名与聚合结果均由后端计算完成，只能直接引用，禁止重新计算）\n");
        sb.append("字段[").append(comparison.getFieldName())
                .append(" fieldCode=").append(comparison.getFieldCode())
                .append(" 类型=").append(comparison.getFieldType()).append("]\n");
        for (CrossProjectComparisonVO.Unit unit : comparison.getUnits()) {
            renderComparisonUnit(sb, comparison, unit, state);
        }
        // 聚合行为后端派生结果，无独立来源证据，不分配 citationId（约束 5）
        renderComparisonAggregates(sb, comparison);
        for (CrossProjectComparisonVO.ExcludedUnit excluded : comparison.getExcluded()) {
            renderExcludedUnit(sb, comparison, excluded, state);
        }
    }

    /**
     * 渲染单个参与单元：排名 + 项目/实例 + 值（rawValue+normalized+单位）+
     * 与当前项目差值（后端已算，追加约束 6）+ 行末 [S{n}] 标记；
     * rank=null（非数值字段）时如实省略名次。
     */
    private void renderComparisonUnit(StringBuilder sb, CrossProjectComparisonVO comparison,
                                      CrossProjectComparisonVO.Unit unit, RenderState state) {
        // 渲染与登记一体：单元先登记取得 [S{n}]，行末追加标记（System Prompt 第 12 条）
        AssistantReferenceVO ref = toComparisonReference(comparison, unit);
        state.registerStructured(ref);
        sb.append("- ");
        if (unit.getRank() != null) {
            sb.append("排名#").append(unit.getRank()).append(' ');
        }
        sb.append("项目[").append(unit.getProjectName()).append(" projectId=")
                .append(unit.getProjectId()).append("] 表单实例=").append(unit.getProjectFormId())
                .append(" 值: ").append(unit.getRawValue());
        if (unit.getNormalizedValue() != null) {
            sb.append("（normalized: ").append(unit.getNormalizedValue());
            if (unit.getUnit() != null) {
                sb.append("，单位: ").append(unit.getUnit());
            }
            sb.append('）');
        }
        if (unit.getDiffFromCurrent() != null) {
            sb.append(" 与当前项目差值: ").append(unit.getDiffFromCurrent());
            if (unit.getDiffFromCurrentPercent() != null) {
                sb.append("（").append(unit.getDiffFromCurrentPercent()).append("）");
            }
        }
        if (unit.getSourceFileName() != null) {
            sb.append(" 来源: ").append(unit.getSourceFileName());
            if (unit.getSourcePage() != null) {
                sb.append(" 第").append(unit.getSourcePage()).append("页");
            }
        }
        sb.append(" [").append(ref.getCitationId()).append("]\n");
    }

    /**
     * 渲染排除单元（追加约束 5：可解释、禁止静默丢弃）：
     * 固定话术"未参与本次数值计算" + 全部来源值明细；排除值继续登记为 S 类证据
     * （约束 6），LLM 可引用其解释"为什么某项目未进入排名"，但 System Prompt
     * 第 11 条禁止将其数值用于重新计算
     */
    private void renderExcludedUnit(StringBuilder sb, CrossProjectComparisonVO comparison,
                                    CrossProjectComparisonVO.ExcludedUnit excluded,
                                    RenderState state) {
        String reasonLabel = switch (excluded.getReason()) {
            case "CONFLICT" -> "该字段存在冲突";
            case "UNPARSEABLE" -> "该字段值无法解析";
            case "UNIT_INCOMPATIBLE" -> "该字段单位与基准单位不一致";
            default -> "该字段无数据";
        };
        sb.append("- ⚠ 项目[").append(excluded.getProjectName()).append(" projectId=")
                .append(excluded.getProjectId()).append("] 表单实例=")
                .append(excluded.getProjectFormId()).append('（').append(reasonLabel)
                .append("），因此未参与本次数值计算\n");
        for (ProjectStructuredFactsVO.ValueItem item : excluded.getValues()) {
            // 渲染与登记一体：每条排除值独立登记（不静默丢弃），行末 [S{n}]
            AssistantReferenceVO ref = toExcludedValueReference(comparison, item);
            state.registerStructured(ref);
            sb.append("  - ").append(renderValueItem(item, ref.getCitationId())).append('\n');
        }
    }

    /**
     * 渲染聚合行（仅数值字段有 aggregates；追加约束 7：avg 固定 scale=4）
     */
    private void renderComparisonAggregates(StringBuilder sb, CrossProjectComparisonVO comparison) {
        CrossProjectComparisonVO.Aggregates aggregates = comparison.getAggregates();
        if (aggregates == null) {
            return;
        }
        sb.append("- 聚合（后端计算）：最大=").append(aggregates.getMax())
                .append("，最小=").append(aggregates.getMin())
                .append("，总和=").append(aggregates.getSum())
                .append("，平均=").append(aggregates.getAvg())
                .append("，数量=").append(aggregates.getCount()).append('\n');
    }

    /**
     * 渲染结构化字段事实段：逐表单实例 → 逐字段；conflict=true 显式前置冲突标记
     * （硬约束 ⑧：Prompt 层面禁止模型裁决，必须列出来源说明）。
     */
    private void renderFacts(StringBuilder sb, ProjectStructuredFactsVO facts, RenderState state) {
        if (facts == null || facts.getForms().isEmpty()) {
            return;
        }
        sb.append("\n【项目结构化字段事实】\n");
        for (ProjectStructuredFactsVO.Form form : facts.getForms()) {
            sb.append("表单实例 ").append(form.getProjectFormId())
                    .append("（formId=").append(form.getFormId()).append("）：\n");
            for (ProjectStructuredFactsVO.Field field : form.getFields()) {
                renderField(sb, field, state);
            }
        }
    }

    /**
     * 渲染单个字段事实：定义快照行 + 冲突标记 + 逐来源值明细
     * （rawValue 展示值与 normalizedValue计算值并列，追加约束 9）+ 行末 [S{n}]。
     */
    private void renderField(StringBuilder sb, ProjectStructuredFactsVO.Field field,
                             RenderState state) {
        sb.append("- 字段[").append(field.getFieldName())
                .append(" fieldCode=").append(field.getFieldCode())
                .append(" 类型=").append(field.getFieldType())
                .append(" 表单实例=").append(field.getProjectFormId()).append("]\n");
        if (field.isConflict()) {
            sb.append("  ⚠ 该字段存在多个不同值（冲突），禁止选择唯一值，必须列出来源说明\n");
        }
        for (ProjectStructuredFactsVO.ValueItem item : field.getValues()) {
            // 渲染与登记一体：每条来源值独立成项（冲突多值全保留，硬约束 ⑧），行末 [S{n}]
            AssistantReferenceVO ref = toStructuredReference(field, item);
            state.registerStructured(ref);
            sb.append("  - ").append(renderValueItem(item, ref.getCitationId())).append('\n');
        }
    }

    /**
     * 渲染单条来源值：值（normalized: x，单位: y）来源: 文件名 第N页 [S{n}]；
     * 缺失的来源元数据（文件名/页码/单位）如实省略，不编造；行末追加引用标记
     * （citationId 由调用方登记取得，渲染与登记一一对应）。
     */
    private String renderValueItem(ProjectStructuredFactsVO.ValueItem item, String citationId) {
        StringBuilder sb = new StringBuilder("值: ").append(item.getRawValue());
        if (item.getNormalizedValue() != null) {
            sb.append("（normalized: ").append(item.getNormalizedValue());
            if (item.getUnit() != null) {
                sb.append("，单位: ").append(item.getUnit());
            }
            sb.append('）');
        } else if (item.getUnit() != null) {
            sb.append("（单位: ").append(item.getUnit()).append('）');
        }
        if (item.getSourceFileName() != null) {
            sb.append(" 来源: ").append(item.getSourceFileName());
        }
        if (item.getSourcePage() != null) {
            sb.append(" 第").append(item.getSourcePage()).append("页");
        }
        sb.append(" [").append(citationId).append(']');
        return sb.toString();
    }

    /**
     * 渲染项目文件清单段（仅文件名；清单为当前版本限制内的一页数据；
     * 清单不构成证据，不登记不标记）。
     */
    private void renderFileList(StringBuilder sb, List<String> fileNames) {
        if (fileNames == null || fileNames.isEmpty()) {
            return;
        }
        sb.append("\n【项目文件清单】\n");
        for (String name : fileNames) {
            sb.append("- ").append(name).append('\n');
        }
    }

    /**
     * 渲染文档检索结果段：[D{n}] 来源: 文件名 第N页 + 原文，
     * 来源元数据缺失时如实省略对应部分；每个切片登记一项 FILE 证据。
     */
    private void renderDocuments(StringBuilder sb, List<Document> documents, RenderState state) {
        if (documents == null || documents.isEmpty()) {
            return;
        }
        sb.append("\n【文档检索结果】\n");
        for (Document doc : documents) {
            ChunkMetadataReader.ChunkSource source = chunkMetadataReader.read(doc);
            String fileName = source.fileName() != null ? source.fileName() : "未知文件";
            String page = source.page() != null ? " 第" + source.page() + "页" : "";
            // 渲染与登记一体：先登记 FILE 证据取得 [D{n}]，再按标记渲染行首编号
            AssistantReferenceVO ref = toFileReference(source, doc.getId());
            state.registerFile(ref);
            sb.append(String.format(DOC_MARKER_FORMAT, ref.getCitationId(), fileName, page, doc.getText()));
        }
    }

    /**
     * 比较参与单元 → STRUCTURED 证据项（fieldCode/fieldName 取比较结果级快照）。
     */
    private AssistantReferenceVO toComparisonReference(CrossProjectComparisonVO comparison,
                                                       CrossProjectComparisonVO.Unit unit) {
        AssistantReferenceVO ref = new AssistantReferenceVO();
        ref.setType(AssistantReferenceVO.TYPE_STRUCTURED);
        ref.setFieldCode(comparison.getFieldCode());
        ref.setFieldName(comparison.getFieldName());
        ref.setRawValue(unit.getRawValue());
        ref.setNormalizedValue(unit.getNormalizedValue());
        ref.setUnit(unit.getUnit());
        ref.setSourceFileId(unit.getSourceFileId());
        ref.setSourceFileName(unit.getSourceFileName());
        ref.setSourcePage(unit.getSourcePage());
        ref.setSourceChunkId(unit.getSourceChunkId());
        return ref;
    }

    /**
     * 排除单元来源值 → STRUCTURED 证据项（fieldCode/fieldName 复用比较结果级快照，
     * excluded 单元与比较结果恒为同一 fieldCode）。
     */
    private AssistantReferenceVO toExcludedValueReference(CrossProjectComparisonVO comparison,
                                                          ProjectStructuredFactsVO.ValueItem item) {
        AssistantReferenceVO ref = new AssistantReferenceVO();
        ref.setType(AssistantReferenceVO.TYPE_STRUCTURED);
        ref.setFieldCode(comparison.getFieldCode());
        ref.setFieldName(comparison.getFieldName());
        ref.setRawValue(item.getRawValue());
        ref.setNormalizedValue(item.getNormalizedValue());
        ref.setUnit(item.getUnit());
        ref.setSourceFileId(item.getSourceFileId());
        ref.setSourceFileName(item.getSourceFileName());
        ref.setSourcePage(item.getSourcePage());
        ref.setSourceChunkId(item.getSourceChunkId());
        return ref;
    }

    /**
     * 结构化值行 → STRUCTURED 证据项。
     */
    private AssistantReferenceVO toStructuredReference(ProjectStructuredFactsVO.Field field,
                                                       ProjectStructuredFactsVO.ValueItem item) {
        AssistantReferenceVO ref = new AssistantReferenceVO();
        ref.setType(AssistantReferenceVO.TYPE_STRUCTURED);
        ref.setFieldCode(field.getFieldCode());
        ref.setFieldName(field.getFieldName());
        ref.setRawValue(item.getRawValue());
        ref.setNormalizedValue(item.getNormalizedValue());
        ref.setUnit(item.getUnit());
        ref.setSourceFileId(item.getSourceFileId());
        ref.setSourceFileName(item.getSourceFileName());
        ref.setSourcePage(item.getSourcePage());
        ref.setSourceChunkId(item.getSourceChunkId());
        return ref;
    }

    /**
     * RAG 切片 → FILE 证据项（metadata 经 ChunkMetadataReader 安全解析，
     * 非法/缺失值如实置 null 不猜测，硬约束 ⑦）。
     */
    private AssistantReferenceVO toFileReference(ChunkMetadataReader.ChunkSource source, String chunkId) {
        AssistantReferenceVO ref = new AssistantReferenceVO();
        ref.setType(AssistantReferenceVO.TYPE_FILE);
        ref.setFileId(source.fileId());
        ref.setFileName(source.fileName());
        ref.setPage(source.page());
        ref.setChunkId(chunkId);
        return ref;
    }

    /**
     * Prompt 构建结果（Phase 11）：用户提示词 + 本次注入的全部证据
     * （evidence 顺序 = 渲染顺序，citationId 与 userPrompt 标记一一对应，
     * 为不可变快照）；Service 据此组装 references/usedFiles/citations。
     */
    public record PromptBuildResult(String userPrompt, List<AssistantReferenceVO> evidence) {
    }

    /**
     * 单次构建的渲染状态：证据登记表 + S/D 双计数器（约束 1：citationId 一次构建内
     * 全局唯一——S 与 D 各自独立递增，同一证据只登记一次；重新 build() 时状态重建、
     * 重新编号）。
     */
    private static final class RenderState {
        /**
         * 已登记证据（顺序 = 渲染顺序）
         */
        private final List<AssistantReferenceVO> evidence = new ArrayList<>();
        private int sCounter;
        private int dCounter;

        /**
         * 登记结构化证据并分配 [S{n}] 标记
         */
        private void registerStructured(AssistantReferenceVO ref) {
            ref.setCitationId("S" + ++sCounter);
            evidence.add(ref);
        }

        /**
         * 登记文档证据并分配 [D{n}] 标记（D 编号独立从 D1 开始，约束 13）
         */
        private void registerFile(AssistantReferenceVO ref) {
            ref.setCitationId("D" + ++dCounter);
            evidence.add(ref);
        }
    }
}
