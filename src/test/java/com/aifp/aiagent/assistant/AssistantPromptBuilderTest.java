package com.aifp.aiagent.assistant;

import com.aifp.aiagent.dto.AssistantReferenceVO;
import com.aifp.aiagent.dto.CrossProjectComparisonVO;
import com.aifp.aiagent.dto.ProjectStructuredFactsVO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link AssistantPromptBuilder} 测试（离线；Phase 8 T3 / Phase 9 历史段扩展 /
 * Phase 11 证据编号登记）
 * <p>
 * 覆盖：System Prompt 反伪造关键规则（追加约束 1 原文、冲突不裁决、无依据话术、
 * rawValue/normalizedValue 规则、纯自然语言、Phase 9 追加约束 1 历史非事实源、
 * Phase 11 第 12 条行内引用标记规则）；
 * User Prompt 分段渲染（对话历史、冲突字段显式标记 + 逐来源明细、[D{n}] 编号、
 * 空段整段省略、文件清单）；
 * Phase 11 证据登记（约束 13 编号漂移覆盖：citationId 唯一、Prompt 标记与 evidence
 * 一一对应、S 跨段连续编号、D 独立从 D1、evidence 顺序 = 渲染顺序、聚合行无标记）。
 *
 * @author Tang_tzb
 */
class AssistantPromptBuilderTest {

    private AssistantPromptBuilder builder;

    @BeforeEach
    void setUp() {
        builder = new AssistantPromptBuilder(new ChunkMetadataReader());
    }

    // ==================== System Prompt：反伪造规则 ====================

    /**
     * System Prompt 必须包含追加约束 1 原文与关键反伪造规则
     */
    @Test
    void systemPrompt_containsAntiFabricationRules() {
        String system = builder.buildSystemPrompt();

        assertThat(system)
                // 追加约束 1 原文
                .contains("你不能访问数据库、Milvus 或其他外部数据；只能使用本次提供的项目事实和文档片段")
                // 追加约束 1 无依据话术
                .contains("当前项目资料中没有找到足够依据")
                // 硬约束 ⑧：冲突不裁决
                .contains("禁止选择唯一值")
                // 追加约束 9：rawValue/normalizedValue 规则
                .contains("rawValue").contains("normalizedValue")
                // 追加约束 6：文档引用给文件名+页码
                .contains("文件名和页码")
                // 追加约束 6/14：无关问题与跨项目不支持
                .contains("不属于当前项目资料范围")
                .contains("当前版本暂不支持")
                // 追加约束 12：纯自然语言
                .contains("禁止输出 JSON")
                // Phase 9 追加约束 1：历史非事实源（即使上轮已回答过也要重新取依据）
                .contains("对话历史仅用于理解当前问题的指代关系")
                .contains("不能作为本次回答的依据")
                // Phase 10 第 11 条：比较数字结论只能引用后端已算结果，禁止自算
                .contains("只能引用【跨项目比较数据】中后端已计算的排名与聚合结果")
                .contains("禁止自行计算、换算或派生任何数字")
                .contains("被标记为排除（冲突/值无法解析/单位不兼容/无数据）的实例必须如实说明其未参与本次数值计算")
                // Phase 11 第 12 条：行内引用标记规则（标记为普通文本 + 禁止编造编号）
                .contains("必须在该句末尾紧跟对应证据的引用标记")
                .contains("[S编号]").contains("[D编号]")
                .contains("只允许使用本次提供的证据中实际存在的标记")
                .contains("禁止编造编号或使用不存在的标记")
                .contains("引用标记属于普通文本");
    }

    // ==================== User Prompt：对话历史段（Phase 9） ====================

    /**
     * 有历史：【对话历史】段渲染于头部之后、事实段之前（时间顺序最近一轮最后），
     * 逐轮"用户：/助手："呈现
     */
    @Test
    void userPrompt_historyRenderedBetweenHeaderAndFacts() {
        ProjectAssistantContext ctx = ProjectAssistantContext.builder()
                .projectName("示范项目")
                .question("那建筑面积呢？")
                .history(List.of(
                        turn("这个项目总投资是多少？", "总投资约100万元。"),
                        turn("项目有哪些文件？", "共有2个文件：预算说明书.pdf、可研报告.pdf。")))
                .facts(factsWithConflict())
                .factsUsed(true)
                .build();

        String user = builder.build(ctx).userPrompt();

        int historyIndex = user.indexOf("【对话历史】");
        int factsIndex = user.indexOf("【项目结构化字段事实】");
        assertThat(historyIndex).isGreaterThan(0).isLessThan(factsIndex);
        assertThat(user)
                .contains("（仅用于理解当前问题的指代关系，不是项目事实来源）")
                .contains("用户：这个项目总投资是多少？")
                .contains("助手：总投资约100万元。")
                .contains("用户：项目有哪些文件？")
                .contains("助手：共有2个文件：预算说明书.pdf、可研报告.pdf。");
        // 时间顺序：第二轮（最近）在第一轮之后
        assertThat(user.indexOf("项目有哪些文件？")).isGreaterThan(user.indexOf("这个项目总投资是多少？"));
    }

    /**
     * 无历史（首轮）：历史段整段省略
     */
    @Test
    void userPrompt_emptyHistoryOmitted() {
        ProjectAssistantContext ctx = ProjectAssistantContext.builder()
                .projectName("示范项目").question("问题")
                .build();

        String user = builder.build(ctx).userPrompt();

        assertThat(user).doesNotContain("【对话历史】");
    }

    // ==================== User Prompt：分段渲染 ====================

    /**
     * 完整 Context：事实（含冲突字段）+ 文件清单 + RAG 命中全渲染，
     * 冲突字段显式标记且多来源值完整呈现
     */
    @Test
    void userPrompt_rendersAllSegmentsWithConflictMarker() {
        ProjectAssistantContext ctx = ProjectAssistantContext.builder()
                .projectName("示范项目")
                .question("这个项目总投资是多少？")
                .facts(factsWithConflict())
                .documents(List.of(doc("切片内容A", "1785800001", "预算说明书.pdf", "3")))
                .fileNames(List.of("预算说明书.pdf", "可研报告.pdf"))
                .factsUsed(true).ragUsed(true).filesUsed(true)
                .build();

        String user = builder.build(ctx).userPrompt();

        // 头部：仅项目名 + 问题
        assertThat(user).startsWith("项目：示范项目\n用户问题：这个项目总投资是多少？");
        // 事实段：字段定义快照 + 冲突标记 + 逐来源明细（rawValue 与 normalized 并列）
        assertThat(user)
                .contains("【项目结构化字段事实】")
                .contains("表单实例 1785600001")
                .contains("fieldCode=total_investment")
                .contains("⚠ 该字段存在多个不同值（冲突），禁止选择唯一值，必须列出来源说明")
                .contains("值: 100万（normalized: 1000000，单位: 万元） 来源: 预算说明书.pdf 第3页")
                .contains("值: 200万（normalized: 2000000，单位: 万元） 来源: 可研报告.pdf 第5页");
        // 文件清单段
        assertThat(user)
                .contains("【项目文件清单】")
                .contains("- 预算说明书.pdf")
                .contains("- 可研报告.pdf");
        // 文档检索结果段：[D{n}] 编号 + 来源（metadata String 化解析）
        assertThat(user)
                .contains("[D1] 来源: 预算说明书.pdf 第3页")
                .contains("切片内容A");
        // 省略段不出现
        assertThat(user).doesNotContain("null");
    }

    /**
     * 空段整段省略：无 facts/无清单/无文档 → 对应标题段不出现
     */
    @Test
    void userPrompt_emptySegmentsOmitted() {
        ProjectAssistantContext ctx = ProjectAssistantContext.builder()
                .projectName("示范项目")
                .question("有哪些文档？")
                .build();

        String user = builder.build(ctx).userPrompt();

        assertThat(user)
                .doesNotContain("【项目结构化字段事实】")
                .doesNotContain("【项目文件清单】")
                .doesNotContain("【文档检索结果】")
                .isEqualTo("项目：示范项目\n用户问题：有哪些文档？\n");
    }

    /**
     * facts 拉取但无 ACTIVE 实例（forms 空数组）→ 事实段省略（非 null 判断）
     */
    @Test
    void userPrompt_emptyFactsOmitted() {
        ProjectStructuredFactsVO facts = new ProjectStructuredFactsVO();
        facts.setForms(new java.util.ArrayList<>());
        ProjectAssistantContext ctx = ProjectAssistantContext.builder()
                .projectName("示范项目").question("问题")
                .facts(facts).factsUsed(true)
                .build();

        String user = builder.build(ctx).userPrompt();

        assertThat(user).doesNotContain("【项目结构化字段事实】");
    }

    /**
     * RAG 命中 metadata 缺失 → 来源部分如实省略（不编造"未知页码"），
     * 文件名缺失兜底"未知文件"
     */
    @Test
    void userPrompt_missingMetadataRenderedSafely() {
        ProjectAssistantContext ctx = ProjectAssistantContext.builder()
                .projectName("示范项目").question("问题")
                .documents(List.of(new Document("无来源切片", Map.of())))
                .ragUsed(true)
                .build();

        String user = builder.build(ctx).userPrompt();

        assertThat(user)
                .contains("[D1] 来源: 未知文件")
                .contains("无来源切片")
                .doesNotContain("第null页");
    }

    /**
     * 多切片编号递增 [D1]、[D2]
     */
    @Test
    void userPrompt_documentNumberingIncrements() {
        ProjectAssistantContext ctx = ProjectAssistantContext.builder()
                .projectName("示范项目").question("问题")
                .documents(List.of(
                        doc("切片一", "1785800001", "文件A.pdf", "1"),
                        doc("切片二", "1785800002", "文件B.pdf", "2")))
                .ragUsed(true)
                .build();

        String user = builder.build(ctx).userPrompt();

        assertThat(user).contains("[D1] 来源: 文件A.pdf 第1页").contains("[D2] 来源: 文件B.pdf 第2页");
    }

    // ==================== User Prompt：跨项目比较数据段（Phase 10） ====================

    /**
     * 比较段渲染（追加约束 5/6/7/11）：标题含"只能直接引用，禁止重新计算"；
     * 参与单元渲染排名/项目/实例/值/差值/来源；聚合行全量呈现（avg scale=4）；
     * 排除单元固定话术"因此未参与本次数值计算"且全部来源值随行；
     * 比较段位于事实段之前
     */
    @Test
    void userPrompt_comparisonRenderedWithUnitsAggregatesAndExcluded() {
        ProjectAssistantContext ctx = ProjectAssistantContext.builder()
                .projectName("示范项目")
                .question("当前项目和项目B总投资比较")
                .comparison(comparison())
                .facts(factsWithConflict())
                .factsUsed(true)
                .build();

        String user = builder.build(ctx).userPrompt();

        assertThat(user)
                .contains("【跨项目比较数据】（以下排名与聚合结果均由后端计算完成，只能直接引用，禁止重新计算）")
                .contains("字段[总投资金额 fieldCode=total_investment 类型=DECIMAL]")
                .contains("- 排名#1 项目[项目B projectId=1785900002] 表单实例=1785600002 值: 200万"
                        + "（normalized: 2000000，单位: 万元） 与当前项目差值: 1000000（0.5）"
                        + " 来源: 项目B预算.pdf 第2页")
                .contains("- 聚合（后端计算）：最大=2000000，最小=1000000，总和=3000000，"
                        + "平均=1500000.0000，数量=2")
                .contains("- ⚠ 项目[示范项目 projectId=1785900001] 表单实例=1785600001"
                        + "（该字段存在冲突），因此未参与本次数值计算")
                .contains("  - 值: 300万（normalized: 3000000，单位: 万元） 来源: 预算说明书.pdf 第3页");
        // 比较段在事实段之前（先给比较结论语境，再给本项目全量事实）
        assertThat(user.indexOf("【跨项目比较数据】")).isLessThan(user.indexOf("【项目结构化字段事实】"));
    }

    /**
     * 非 COMPARISON 意图（comparison=null）→ 比较段整段省略
     */
    @Test
    void userPrompt_nullComparisonOmitted() {
        ProjectAssistantContext ctx = ProjectAssistantContext.builder()
                .projectName("示范项目").question("问题")
                .build();

        assertThat(builder.build(ctx).userPrompt()).doesNotContain("【跨项目比较数据】");
    }

    /**
     * rank=null（非数值字段仅列值）→ 名次如实省略；无聚合（aggregates=null）→
     * 聚合行省略；差值 null → 差值部分省略
     */
    @Test
    void userPrompt_nonNumericComparisonOmitsRankAndAggregates() {
        CrossProjectComparisonVO comparison = comparison();
        comparison.setFieldType("STRING");
        comparison.setAggregates(null);
        comparison.getUnits().get(0).setRank(null);
        comparison.getUnits().get(0).setDiffFromCurrent(null);
        comparison.getUnits().get(0).setDiffFromCurrentPercent(null);
        ProjectAssistantContext ctx = ProjectAssistantContext.builder()
                .projectName("示范项目").question("比较")
                .comparison(comparison)
                .build();

        String user = builder.build(ctx).userPrompt();

        assertThat(user)
                .contains("- 项目[项目B projectId=1785900002]")
                .doesNotContain("排名#")
                .doesNotContain("聚合（后端计算）")
                .doesNotContain("与当前项目差值");
    }

    // ==================== 证据编号登记与引用溯源（Phase 11） ====================

    /**
     * 标记位置：结构化值行末 [S{n}]、文档行首 [D{n}]（与 System Prompt 第 12 条一致）
     */
    @Test
    void build_markerPositions_endOfStructuredLine_startOfDocumentLine() {
        ProjectAssistantContext ctx = ProjectAssistantContext.builder()
                .projectName("示范项目")
                .question("这个项目总投资是多少？")
                .facts(factsWithConflict())
                .factsUsed(true)
                .documents(List.of(doc("切片内容A", "1785800001", "预算说明书.pdf", "3")))
                .ragUsed(true)
                .build();

        AssistantPromptBuilder.PromptBuildResult result = builder.build(ctx);

        assertThat(result.userPrompt())
                .contains("值: 100万（normalized: 1000000，单位: 万元） 来源: 预算说明书.pdf 第3页 [S1]")
                .contains("值: 200万（normalized: 2000000，单位: 万元） 来源: 可研报告.pdf 第5页 [S2]")
                .contains("[D1] 来源: 预算说明书.pdf 第3页");
    }

    /**
     * 约束 13 编号漂移覆盖：comparison 单元 → excluded 值 → facts 值 S 连续编号、
     * D 独立从 D1、citationId 全局唯一、Prompt 中全部标记与 evidence 一一对应、
     * evidence 顺序 = 渲染顺序（类型语义：STRUCTURED 前置、FILE 在后）
     */
    @Test
    void build_citationIds_uniqueContinuousAndMatchPromptMarkers() {
        ProjectAssistantContext ctx = ProjectAssistantContext.builder()
                .projectName("示范项目")
                .question("当前项目和项目B总投资比较")
                .comparison(comparison())
                .facts(factsWithConflict())
                .factsUsed(true)
                .documents(List.of(
                        doc("切片一", "1785800001", "文件A.pdf", "1"),
                        doc("切片二", "1785800002", "文件B.pdf", "2")))
                .ragUsed(true)
                .build();

        AssistantPromptBuilder.PromptBuildResult result = builder.build(ctx);

        // S 连续编号：比较单元 S1 → 排除值 S2 → facts 值 S3/S4；D 独立从 D1
        assertThat(result.evidence())
                .extracting(AssistantReferenceVO::getCitationId)
                .containsExactly("S1", "S2", "S3", "S4", "D1", "D2");
        assertThat(result.evidence())
                .extracting(AssistantReferenceVO::getCitationId)
                .doesNotHaveDuplicates();
        assertThat(result.evidence())
                .extracting(AssistantReferenceVO::getType)
                .containsExactly(
                        AssistantReferenceVO.TYPE_STRUCTURED, AssistantReferenceVO.TYPE_STRUCTURED,
                        AssistantReferenceVO.TYPE_STRUCTURED, AssistantReferenceVO.TYPE_STRUCTURED,
                        AssistantReferenceVO.TYPE_FILE, AssistantReferenceVO.TYPE_FILE);
        // Prompt 中出现的全部标记与 evidence 一一对应（编号漂移防线）
        List<String> promptMarkers = extractMarkers(result.userPrompt());
        List<String> evidenceIds = result.evidence().stream()
                .map(AssistantReferenceVO::getCitationId).toList();
        assertThat(promptMarkers).containsExactlyInAnyOrderElementsOf(evidenceIds);
    }

    /**
     * 聚合行不分配 citationId（约束 5：派生结果无独立来源证据）
     */
    @Test
    void build_aggregateLineHasNoCitationMarker() {
        ProjectAssistantContext ctx = ProjectAssistantContext.builder()
                .projectName("示范项目")
                .question("比较")
                .comparison(comparison())
                .build();

        AssistantPromptBuilder.PromptBuildResult result = builder.build(ctx);

        assertThat(result.evidence())
                .extracting(AssistantReferenceVO::getCitationId)
                .containsExactly("S1", "S2");
        // 聚合行以"数量=2"收尾且无行末标记
        assertThat(result.userPrompt())
                .contains("- 聚合（后端计算）：最大=2000000，最小=1000000，总和=3000000，"
                        + "平均=1500000.0000，数量=2\n");
    }

    /**
     * 全空 Context：零证据登记（evidence 空列表）、Prompt 无任何标记
     */
    @Test
    void build_emptyContext_registersNoEvidence() {
        ProjectAssistantContext ctx = ProjectAssistantContext.builder()
                .projectName("示范项目").question("有哪些文档？")
                .build();

        AssistantPromptBuilder.PromptBuildResult result = builder.build(ctx);

        assertThat(result.evidence()).isEmpty();
        assertThat(result.userPrompt()).doesNotContain("[S").doesNotContain("[D");
    }

    // ==================== 测试辅助 ====================

    /**
     * 提取 Prompt 中全部 [S{n}]/[D{n}] 标记（编号漂移断言用）
     */
    private List<String> extractMarkers(String text) {
        List<String> markers = new ArrayList<>();
        Matcher matcher = Pattern.compile("\\[([SD]\\d+)]").matcher(text);
        while (matcher.find()) {
            markers.add(matcher.group(1));
        }
        return markers;
    }

    /**
     * 单实例单字段（冲突）双来源事实
     */
    private ProjectStructuredFactsVO factsWithConflict() {
        ProjectStructuredFactsVO.Field field = new ProjectStructuredFactsVO.Field();
        field.setProjectFormId(1785600001L);
        field.setFieldCode("total_investment");
        field.setFieldName("总投资金额");
        field.setFieldType("DECIMAL");
        field.setConflict(true);
        field.getValues().add(value("100万", "1000000", 1785800001L, "预算说明书.pdf", 3));
        field.getValues().add(value("200万", "2000000", 1785800002L, "可研报告.pdf", 5));

        ProjectStructuredFactsVO.Form form = new ProjectStructuredFactsVO.Form();
        form.setProjectFormId(1785600001L);
        form.setFormId(1785700001L);
        form.setVersion(1);
        form.setStatus("ACTIVE");
        form.getFields().add(field);

        ProjectStructuredFactsVO facts = new ProjectStructuredFactsVO();
        facts.getForms().add(form);
        return facts;
    }

    private ProjectStructuredFactsVO.ValueItem value(String raw, String normalized,
                                                     Long fileId, String fileName, Integer page) {
        ProjectStructuredFactsVO.ValueItem item = new ProjectStructuredFactsVO.ValueItem();
        item.setRawValue(raw);
        item.setNormalizedValue(normalized);
        item.setUnit("万元");
        item.setSourceFileId(fileId);
        item.setSourceFileName(fileName);
        item.setSourcePage(page);
        item.setSourceChunkId("chunk-" + fileId);
        item.setConfidence(new BigDecimal("0.95"));
        return item;
    }

    /**
     * Phase 5 约定：metadata 值统一 String 化
     */
    private Document doc(String content, String fileId, String fileName, String pageStart) {
        return new Document(content, Map.of(
                "fileId", fileId,
                "fileName", fileName,
                "pageStart", pageStart));
    }

    /**
     * Phase 9 对话轮次构造
     */
    private ConversationTurn turn(String question, String answer) {
        return ConversationTurn.builder()
                .userQuestion(question)
                .assistantAnswer(answer)
                .createTime(java.time.LocalDateTime.now())
                .build();
    }

    /**
     * 跨项目比较结果样本（Phase 10）：1 参与单元（项目B，rank=1、diff=1000000、
     * percent=0.5）+ 聚合（avg=1500000.0000）+ 1 冲突排除单元（当前项目，含 1 来源值）
     */
    private CrossProjectComparisonVO comparison() {
        CrossProjectComparisonVO.Unit unit = new CrossProjectComparisonVO.Unit();
        unit.setProjectId(1785900002L);
        unit.setProjectName("项目B");
        unit.setProjectFormId(1785600002L);
        unit.setRawValue("200万");
        unit.setNormalizedValue("2000000");
        unit.setUnit("万元");
        unit.setRank(1);
        unit.setDiffFromCurrent(new BigDecimal("1000000"));
        unit.setDiffFromCurrentPercent(new BigDecimal("0.5"));
        unit.setSourceFileName("项目B预算.pdf");
        unit.setSourcePage(2);

        CrossProjectComparisonVO.Aggregates aggregates = new CrossProjectComparisonVO.Aggregates();
        aggregates.setMax(new BigDecimal("2000000"));
        aggregates.setMin(new BigDecimal("1000000"));
        aggregates.setSum(new BigDecimal("3000000"));
        aggregates.setAvg(new BigDecimal("1500000.0000"));
        aggregates.setCount(2);

        ProjectStructuredFactsVO.ValueItem excludedValue = new ProjectStructuredFactsVO.ValueItem();
        excludedValue.setRawValue("300万");
        excludedValue.setNormalizedValue("3000000");
        excludedValue.setUnit("万元");
        excludedValue.setSourceFileName("预算说明书.pdf");
        excludedValue.setSourcePage(3);
        CrossProjectComparisonVO.ExcludedUnit excluded = new CrossProjectComparisonVO.ExcludedUnit();
        excluded.setReason("CONFLICT");
        excluded.setProjectId(1785900001L);
        excluded.setProjectName("示范项目");
        excluded.setProjectFormId(1785600001L);
        excluded.setFieldCode("total_investment");
        excluded.getValues().add(excludedValue);

        CrossProjectComparisonVO comparison = new CrossProjectComparisonVO();
        comparison.setFieldCode("total_investment");
        comparison.setFieldName("总投资金额");
        comparison.setFieldType("DECIMAL");
        comparison.setCurrentProjectId(1785900001L);
        comparison.getTargetProjectIds().add(1785900002L);
        comparison.setAggregates(aggregates);
        comparison.getUnits().add(unit);
        comparison.getExcluded().add(excluded);
        return comparison;
    }
}
