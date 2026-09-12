package com.aifp.aiagent.assistant;

import com.aifp.aiagent.dto.ProjectStructuredFactsVO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link AssistantPromptBuilder} 测试（离线；Phase 8 T3 / Phase 9 历史段扩展）
 * <p>
 * 覆盖：System Prompt 反伪造关键规则（追加约束 1 原文、冲突不裁决、无依据话术、
 * rawValue/normalizedValue 规则、纯自然语言、Phase 9 追加约束 1 历史非事实源）；
 * User Prompt 分段渲染（对话历史、冲突字段显式标记 + 逐来源明细、[D{n}] 编号、
 * 空段整段省略、文件清单）。
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
                .contains("不能作为本次回答的依据");
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

        String user = builder.buildUserPrompt(ctx);

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

        String user = builder.buildUserPrompt(ctx);

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

        String user = builder.buildUserPrompt(ctx);

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

        String user = builder.buildUserPrompt(ctx);

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

        String user = builder.buildUserPrompt(ctx);

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

        String user = builder.buildUserPrompt(ctx);

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

        String user = builder.buildUserPrompt(ctx);

        assertThat(user).contains("[D1] 来源: 文件A.pdf 第1页").contains("[D2] 来源: 文件B.pdf 第2页");
    }

    // ==================== 测试辅助 ====================

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
}
