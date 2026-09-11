package com.aifp.aiagent.service.impl;

import com.aifp.aiagent.common.ResultCode;
import com.aifp.aiagent.dto.ExtractionResult;
import com.aifp.aiagent.dto.FileRecordVO;
import com.aifp.aiagent.dto.FormFieldVO;
import com.aifp.aiagent.dto.FormVO;
import com.aifp.aiagent.entity.enums.FileStatus;
import com.aifp.aiagent.exception.BusinessException;
import com.aifp.aiagent.rag.*;
import com.aifp.aiagent.service.FieldExtractorService;
import com.aifp.aiagent.service.FileService;
import com.aifp.aiagent.service.FormService;
import com.aifp.aiagent.service.ProjectFormPersistenceService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.document.Document;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * FieldExtractorService 实现
 * <p>
 * 流程：ingest(幂等) → SUCCESS 预检 → EXTRACTING → 按字段检索+合并去重 chunks →
 * 动态 Prompt → ChatModel 调用 → JSON 解析 → Schema 校验+智能类型转换 →
 * 失败按字段错误反馈 Retry → {@link ExtractionResult} → SUCCESS。
 * <p>
 * 阶段 13 状态机合规：SUCCESS 为终态——已成功文件重新抽取合法但不写状态
 * （不产生 SUCCESS → EXTRACTING 倒退）；抽取失败经 markFailed 落 FAILED，
 * 覆盖异步任务与 /fill 直调两条路径。
 *
 * @author Tang_tzb
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FieldExtractorServiceImpl implements FieldExtractorService {

    private final DocumentIngestionService ingestionService;
    private final FormService formService;
    private final FileService fileService;
    private final VectorStoreService vectorStoreService;
    private final FieldQueryGenerator fieldQueryGenerator;
    private final ExtractionPromptBuilder extractionPromptBuilder;
    private final FieldSchemaValidator fieldSchemaValidator;
    private final ChatModel chatModel;
    private final ObjectMapper objectMapper;
    private final ProjectFormPersistenceService projectFormPersistenceService;

    @Value("${rag.retrieve.topK:5}")
    private int topK;

    /**
     * 抽取校验失败后的最大重试次数（含首次共 maxAttempts+1 次 LLM 调用）
     */
    @Value("${rag.extract.retry.max-attempts:2}")
    private int maxAttempts;

    @Override
    public ExtractionResult extract(Long formId, Long fileId) {
        // 需求 §九：历史 API 兼容——根据 fileId 解析归属 projectId 后走项目链路
        // （文件未归属项目时 projectId=null，跳过项目域持久化，抽取行为不变）
        FileRecordVO file = fileService.getById(fileId);
        return extract(file == null ? null : file.getProjectId(), formId, fileId);
    }

    @Override
    public ExtractionResult extract(Long projectId, Long formId, Long fileId) {
        // 幂等：确保文档已入 Milvus（已入库则跳过；/fill 直调路径依赖本调用）
        ingestionService.ingest(fileId);
        FileRecordVO file = fileService.getById(fileId);
        ensureFileProjectConsistency(projectId, file);
        // 阶段 13 状态机适配：SUCCESS 为终态不可逆。换表单重新抽取合法，
        // 但跳过 EXTRACTING/SUCCESS 状态写，避免 SUCCESS → EXTRACTING 非法跳转。
        boolean alreadySuccess = file.getStatus() == FileStatus.SUCCESS;
        try {
            ExtractionResult result = doExtract(projectId, formId, fileId, alreadySuccess);
            log.info("字段抽取完成 formId={}, fileId={}, projectId={}, attempts={}, errors={}",
                    formId, fileId, projectId, result.getAttemptsUsed(), result.getErrors().size());
            return result;
        } catch (Exception e) {
            // 阶段 13：抽取失败（AI 调用/检索为空/Retry 耗尽/持久化失败）落 FAILED，
            // 异常原样重抛保持对外契约不变；SUCCESS 文件重抽取失败不改状态。
            markFailed(fileId, alreadySuccess);
            throw e;
        }
    }

    /**
     * 项目归属一致性校验：显式 projectId 与文件归属均非空且不一致时拒绝
     * （与 ParseTaskServiceImpl.ensureFileInProject 同语义，防跨项目错绑）。
     */
    private void ensureFileProjectConsistency(Long projectId, FileRecordVO file) {
        if (projectId != null && file.getProjectId() != null
                && !projectId.equals(file.getProjectId())) {
            throw new BusinessException(ResultCode.PROJECT_FILE_NOT_IN_PROJECT);
        }
    }

    /**
     * 实际抽取流程：EXTRACTING → 检索合并 → Retry 循环 → 持久化 → SUCCESS。
     * <p>
     * 业务闭环约束：项目字段值持久化先于 SUCCESS 状态写——持久化失败时异常外抛，
     * 由 {@code extract} 失败链路接管（落 FAILED），保证不出现
     * "文件 SUCCESS 但项目字段值未落库"。
     *
     * @param projectId       项目ID（null 跳过持久化，历史兼容）
     * @param skipStatusWrites SUCCESS 终态文件重抽取时跳过状态写
     */
    private ExtractionResult doExtract(Long projectId, Long formId, Long fileId, boolean skipStatusWrites) {
        List<FormFieldVO> fields = loadFields(formId);
        if (!skipStatusWrites) {
            fileService.updateStatus(fileId, FileStatus.EXTRACTING);
        }

        // 按字段检索 + 合并去重（带 fileId 过滤避免跨文件污染；项目范围过滤属后续 Phase，不得提前加）
        String filter = "fileId == '" + fileId + "'";
        List<Document> chunks = retrieveAndMerge(fields, filter);
        if (chunks.isEmpty()) {
            throw new BusinessException(ResultCode.VECTOR_RETRIEVE_ERROR, "未检索到相关文档切片");
        }
        // 片段编号索引：与 ExtractionPromptBuilder 的 [C{n}] 编号规则一致（同下标 i+1），修改需同步
        Map<String, Document> markerToDoc = buildMarkerIndex(chunks);

        // Retry 循环：LLM 调用 → JSON 解析 → Schema 校验 → 失败反馈重试
        ExtractionResult result = extractWithRetry(fields, chunks, markerToDoc);
        if (projectId != null) {
            projectFormPersistenceService.persistExtraction(projectId, formId, fileId, fields, result);
        }
        if (!skipStatusWrites) {
            fileService.updateStatus(fileId, FileStatus.SUCCESS);
        }
        return result;
    }

    /**
     * 构建 片段编号→Document 映射（C1..Cn 按列表顺序，供 LLM 引用溯源解析）。
     */
    private Map<String, Document> buildMarkerIndex(List<Document> chunks) {
        Map<String, Document> markerToDoc = new LinkedHashMap<>();
        for (int i = 0; i < chunks.size(); i++) {
            markerToDoc.put("C" + (i + 1), chunks.get(i));
        }
        return markerToDoc;
    }

    /**
     * 标记文件为失败状态，吞掉二次异常避免覆盖原始异常；
     * SUCCESS 终态文件重抽取失败保持 SUCCESS（终态封闭），不落 FAILED。
     */
    private void markFailed(Long fileId, boolean alreadySuccess) {
        if (alreadySuccess) {
            return;
        }
        try {
            fileService.updateStatus(fileId, FileStatus.FAILED);
        } catch (Exception ex) {
            log.warn("标记失败状态异常 fileId={}", fileId, ex);
        }
    }

    /**
     * Retry 循环：每次校验失败把字段错误反馈给 AI 修正，直至无错误或耗尽重试次数。
     * JSON 解析失败立即抛 3002，不进入字段级 Retry（属响应格式问题，非字段问题）。
     * <p>
     * sources 为旁路软依赖：validator 只遍历字段定义，LLM 多返回的 sources 键被天然忽略，
     * 缺失/非法不影响抽取成功语义（不修改既有 JSON 校验契约）。
     */
    private ExtractionResult extractWithRetry(List<FormFieldVO> fields, List<Document> chunks,
                                              Map<String, Document> markerToDoc) {
        String userPrompt = extractionPromptBuilder.buildUserPrompt(chunks);
        int totalCalls = maxAttempts + 1;
        FieldSchemaValidator.ValidationResult vr = null;
        Map<String, String> citedMarkers = Map.of();
        String feedback = null;
        int used = 0;
        for (int i = 0; i < totalCalls; i++) {
            used = i + 1;
            String systemPrompt = extractionPromptBuilder.buildSystemPrompt(fields, feedback);
            Map<String, Object> raw = parseJson(callChatModel(systemPrompt, userPrompt));
            citedMarkers = extractCitedMarkers(raw);
            vr = fieldSchemaValidator.validate(raw, fields);
            if (!vr.hasErrors()) {
                return buildResult(vr, citedMarkers, markerToDoc, used);
            }
            feedback = extractionPromptBuilder.buildRetryFeedback(vr.getErrors());
        }
        return buildResult(vr, citedMarkers, markerToDoc, used);
    }

    /**
     * 提取 LLM 返回的 sources 旁路键（fieldCode → 片段编号如 "C2"）；
     * 缺失或非法形态返回空 Map（软依赖，不影响主流程）。
     */
    private Map<String, String> extractCitedMarkers(Map<String, Object> raw) {
        Object obj = raw == null ? null : raw.get("sources");
        if (!(obj instanceof Map<?, ?> map)) {
            return Map.of();
        }
        Map<String, String> markers = new LinkedHashMap<>();
        for (Map.Entry<?, ?> e : map.entrySet()) {
            if (e.getKey() != null && e.getValue() != null) {
                markers.put(String.valueOf(e.getKey()), String.valueOf(e.getValue()));
            }
        }
        return markers;
    }

    /**
     * 组装 {@link ExtractionResult}：类型化值 + LLM 原始值快照 + 引用式溯源 + 字段错误。
     * 溯源解析：sourceChunkId=Document.getId()、sourcePage=被引用 Chunk metadata；
     * 未知编号不猜测、缺 metadata 省略（禁止按下标/相似度猜来源）。
     */
    private ExtractionResult buildResult(FieldSchemaValidator.ValidationResult vr,
                                         Map<String, String> citedMarkers,
                                         Map<String, Document> markerToDoc, int used) {
        ExtractionResult r = new ExtractionResult();
        r.setValues(vr.getCoerced());
        r.setRawValues(vr.getRaws());
        r.setErrors(vr.getErrors());
        r.setAttemptsUsed(used);
        fillSourceRefs(r, citedMarkers, markerToDoc);
        return r;
    }

    /**
     * 解析引用编号为溯源事实并装入结果：仅当编号能命中片段索引时记录，
     * sourcePage 取被引用 Chunk metadata 的 pageStart（阶段 12 语义）或 page（旧切片路径）。
     */
    private void fillSourceRefs(ExtractionResult r, Map<String, String> citedMarkers,
                                Map<String, Document> markerToDoc) {
        Map<String, String> sources = new LinkedHashMap<>();
        Map<String, Integer> sourcePages = new LinkedHashMap<>();
        for (Map.Entry<String, String> e : citedMarkers.entrySet()) {
            Document doc = markerToDoc.get(e.getValue());
            if (doc == null) {
                continue;
            }
            sources.put(e.getKey(), doc.getId());
            Integer page = resolveChunkPage(doc);
            if (page != null) {
                sourcePages.put(e.getKey(), page);
            }
        }
        r.setSources(sources);
        r.setSourcePages(sourcePages);
    }

    /**
     * 从被引用 Chunk 的 metadata 解析来源页码：优先 pageStart（PDF 路径），回退 page（旧路径）。
     */
    private Integer resolveChunkPage(Document doc) {
        Object page = doc.getMetadata().get("pageStart");
        if (page == null) {
            page = doc.getMetadata().get("page");
        }
        if (page instanceof Integer i) {
            return i;
        }
        return page instanceof Number n ? n.intValue() : null;
    }

    /**
     * 读取表单字段定义；表单不存在或字段为空抛业务异常。
     */
    private List<FormFieldVO> loadFields(Long formId) {
        FormVO form = formService.getFormById(formId);
        List<FormFieldVO> fields = form.getFields();
        if (fields == null || fields.isEmpty()) {
            throw new BusinessException(ResultCode.FORM_FIELD_EMPTY);
        }
        return fields;
    }

    /**
     * 逐字段检索并按 Document.id 去重，保序合并。
     */
    private List<Document> retrieveAndMerge(List<FormFieldVO> fields, String filter) {
        Map<String, Document> dedup = new LinkedHashMap<>();
        for (FormFieldVO field : fields) {
            String query = fieldQueryGenerator.generate(field);
            List<Document> hits = vectorStoreService.search(query, topK, filter);
            for (Document d : hits) {
                dedup.putIfAbsent(d.getId(), d);
            }
        }
        return new ArrayList<>(dedup.values());
    }

    /**
     * 调用 ChatModel，返回文本内容。
     */
    private String callChatModel(String systemPrompt, String userPrompt) {
        try {
            Prompt prompt = new Prompt(List.of(
                    new SystemMessage(systemPrompt),
                    new UserMessage(userPrompt)));
            ChatResponse response = chatModel.call(prompt);
            return response.getResult().getOutput().getText();
        } catch (Exception e) {
            log.error("AI 模型调用失败: {}", e.getMessage(), e);
            throw new BusinessException(ResultCode.AI_INVOKE_ERROR, "AI 抽取调用失败");
        }
    }

    /**
     * 解析 LLM 返回的 JSON 文本为 Map，剥离可能的 markdown 代码围栏。
     */
    private Map<String, Object> parseJson(String json) {
        try {
            String cleaned = stripCodeFence(json);
            return objectMapper.readValue(cleaned, new TypeReference<>() {
            });
        } catch (Exception e) {
            log.error("AI 响应 JSON 解析失败: raw={}", json, e);
            throw new BusinessException(ResultCode.AI_RESPONSE_PARSE_ERROR, "AI 响应解析失败");
        }
    }

    /**
     * 剥离 ```json ... ``` 围栏。
     */
    private String stripCodeFence(String text) {
        String t = text == null ? "" : text.trim();
        if (t.startsWith("```")) {
            int firstNewline = t.indexOf('\n');
            if (firstNewline > 0) {
                t = t.substring(firstNewline + 1);
            }
            int lastFence = t.lastIndexOf("```");
            if (lastFence >= 0) {
                t = t.substring(0, lastFence);
            }
            t = t.trim();
        }
        return t;
    }
}
