package com.aifp.aiagent.assistant;

import com.aifp.aiagent.dto.ProjectStructuredFactsVO;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.document.Document;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 项目助手回答 Prompt 构建器（需求 §二十二/§二十三）
 * <p>
 * System Prompt = 反伪造规则常量（追加约束 1/6/9/12/14 + 硬约束 ⑧ 冲突不裁决）；
 * User Prompt = 按上下文分段渲染（空段整段省略）：结构化字段事实（冲突显式标记 +
 * 逐来源明细）/ 项目文件清单 / 文档检索结果（[D{n}] 编号，与 ExtractionPromptBuilder
 * [C{n}] 同风格，修改编号语义需互相同步）。
 * <p>
 * 职责边界（追加约束 10/12）：只做数据渲染，不解析业务数字、不组装响应 VO；
 * 回答保持纯自然语言，references/structuredData/usedFiles 全部由后端代码组装。
 *
 * @author Tang_tzb
 */
@Component
@RequiredArgsConstructor
public class AssistantPromptBuilder {

    /**
     * 文档片段行前缀（编号与 ExtractionPromptBuilder 的 [C{n}] 同风格）
     */
    private static final String DOC_MARKER_FORMAT = "[D%d] 来源: %s%s%n%s";

    /**
     * 反伪造 System Prompt（§二十三 1-10 条全量；措辞为追加约束 1 原文要求，
     * 修改需同步评估 Prompt 效果与单元测试断言）
     */
    private static final String SYSTEM_PROMPT = """
            你是项目资料智能助手，负责回答用户关于当前项目的问题。你必须严格遵守以下规则：
            1. 你不能访问数据库、Milvus 或其他外部数据；只能使用本次提供的项目事实和文档片段。
            2. 项目事实（金额、面积、日期、单位、数量等）只能来自本次提供的【项目结构化字段事实】和【文档检索结果】；无法从本次提供的资料中得到依据的项目事实，必须回答"当前项目资料中没有找到足够依据"，禁止依据自身知识编造或补充。
            3. 不确定或资料不足以完整回答时，必须如实说明，禁止猜测。
            4. 被标记为"冲突"的字段存在多个不同来源值：必须完整列出全部值及其来源文件与页码，禁止选择唯一值，禁止裁决哪个正确。
            5. 展示数值时优先使用 rawValue（用户可读值）+ unit（单位）；涉及比较或计算时只能使用 normalizedValue（后端标准值），禁止自行换算单位或解析业务数字。
            6. 引用文档内容时必须给出文件名和页码，便于用户核对。
            7. 与当前项目明显无关的问题，如实回答"不属于当前项目资料范围"，不要强行作答或编造。
            8. 涉及项目之间比较、排序、统计、筛选和归因分析的问题，如实说明当前版本暂不支持此类能力。
            9. 只输出纯自然语言回答，禁止输出 JSON 或其他结构化格式。""";

    private final ChunkMetadataReader chunkMetadataReader;

    /**
     * 构建反伪造 System Prompt。
     *
     * @return 系统提示词常量
     */
    public String buildSystemPrompt() {
        return SYSTEM_PROMPT;
    }

    /**
     * 构建用户 Prompt：按上下文分段渲染，空段整段省略。
     *
     * @param ctx 助手上下文（由 ServiceImpl 按 QueryPlan 组装）
     * @return 用户提示词
     */
    public String buildUserPrompt(ProjectAssistantContext ctx) {
        StringBuilder sb = new StringBuilder();
        sb.append("项目：").append(ctx.getProjectName()).append('\n');
        sb.append("用户问题：").append(ctx.getQuestion()).append('\n');
        renderFacts(sb, ctx.getFacts());
        renderFileList(sb, ctx.getFileNames());
        renderDocuments(sb, ctx.getDocuments());
        return sb.toString();
    }

    // ==================== 内部方法 ====================

    /**
     * 渲染结构化字段事实段：逐表单实例 → 逐字段；conflict=true 显式前置冲突标记
     * （硬约束 ⑧：Prompt 层面禁止模型裁决，必须列出来源说明）。
     */
    private void renderFacts(StringBuilder sb, ProjectStructuredFactsVO facts) {
        if (facts == null || facts.getForms().isEmpty()) {
            return;
        }
        sb.append("\n【项目结构化字段事实】\n");
        for (ProjectStructuredFactsVO.Form form : facts.getForms()) {
            sb.append("表单实例 ").append(form.getProjectFormId())
                    .append("（formId=").append(form.getFormId()).append("）：\n");
            for (ProjectStructuredFactsVO.Field field : form.getFields()) {
                renderField(sb, field);
            }
        }
    }

    /**
     * 渲染单个字段事实：定义快照行 + 冲突标记 + 逐来源值明细
     * （rawValue 展示值与 normalizedValue计算值并列，追加约束 9）。
     */
    private void renderField(StringBuilder sb, ProjectStructuredFactsVO.Field field) {
        sb.append("- 字段[").append(field.getFieldName())
                .append(" fieldCode=").append(field.getFieldCode())
                .append(" 类型=").append(field.getFieldType())
                .append(" 表单实例=").append(field.getProjectFormId()).append("]\n");
        if (field.isConflict()) {
            sb.append("  ⚠ 该字段存在多个不同值（冲突），禁止选择唯一值，必须列出来源说明\n");
        }
        for (ProjectStructuredFactsVO.ValueItem item : field.getValues()) {
            sb.append("  - ").append(renderValueItem(item)).append('\n');
        }
    }

    /**
     * 渲染单条来源值：值（normalized: x，单位: y）来源: 文件名 第N页；
     * 缺失的来源元数据（文件名/页码/单位）如实省略，不编造。
     */
    private String renderValueItem(ProjectStructuredFactsVO.ValueItem item) {
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
        return sb.toString();
    }

    /**
     * 渲染项目文件清单段（仅文件名；清单为当前版本限制内的一页数据）。
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
     * 来源元数据缺失时如实省略对应部分。
     */
    private void renderDocuments(StringBuilder sb, List<Document> documents) {
        if (documents == null || documents.isEmpty()) {
            return;
        }
        sb.append("\n【文档检索结果】\n");
        int index = 1;
        for (Document doc : documents) {
            ChunkMetadataReader.ChunkSource source = chunkMetadataReader.read(doc);
            String fileName = source.fileName() != null ? source.fileName() : "未知文件";
            String page = source.page() != null ? " 第" + source.page() + "页" : "";
            sb.append(String.format(DOC_MARKER_FORMAT, index, fileName, page, doc.getText()));
            index++;
        }
    }
}
