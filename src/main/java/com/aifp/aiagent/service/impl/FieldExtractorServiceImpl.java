package com.aifp.aiagent.service.impl;

import com.aifp.aiagent.common.ResultCode;
import com.aifp.aiagent.dto.ExtractionResult;
import com.aifp.aiagent.dto.FieldError;
import com.aifp.aiagent.dto.FormFieldVO;
import com.aifp.aiagent.dto.FormVO;
import com.aifp.aiagent.entity.enums.FileStatus;
import com.aifp.aiagent.exception.BusinessException;
import com.aifp.aiagent.rag.*;
import com.aifp.aiagent.service.FieldExtractorService;
import com.aifp.aiagent.service.FileService;
import com.aifp.aiagent.service.FormService;
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

    @Value("${rag.retrieve.topK:5}")
    private int topK;

    /**
     * 抽取校验失败后的最大重试次数（含首次共 maxAttempts+1 次 LLM 调用）
     */
    @Value("${rag.extract.retry.max-attempts:2}")
    private int maxAttempts;

    @Override
    public ExtractionResult extract(Long formId, Long fileId) {
        // 幂等：确保文档已入 Milvus（已入库则跳过）
        ingestionService.ingest(fileId);
        // 阶段 13 状态机适配：SUCCESS 为终态不可逆。换表单重新抽取合法，
        // 但跳过 EXTRACTING/SUCCESS 状态写，避免 SUCCESS → EXTRACTING 非法跳转。
        boolean alreadySuccess = fileService.getById(fileId).getStatus() == FileStatus.SUCCESS;
        try {
            ExtractionResult result = doExtract(formId, fileId, alreadySuccess);
            log.info("字段抽取完成 formId={}, fileId={}, attempts={}, errors={}",
                    formId, fileId, result.getAttemptsUsed(), result.getErrors().size());
            return result;
        } catch (Exception e) {
            // 阶段 13：抽取失败（AI 调用/检索为空/Retry 耗尽）落 FAILED，
            // 异常原样重抛保持对外契约不变；SUCCESS 文件重抽取失败不改状态。
            markFailed(fileId, alreadySuccess);
            throw e;
        }
    }

    /**
     * 实际抽取流程：EXTRACTING → 检索合并 → Retry 循环 → SUCCESS。
     *
     * @param skipStatusWrites SUCCESS 终态文件重抽取时跳过状态写
     */
    private ExtractionResult doExtract(Long formId, Long fileId, boolean skipStatusWrites) {
        List<FormFieldVO> fields = loadFields(formId);
        if (!skipStatusWrites) {
            fileService.updateStatus(fileId, FileStatus.EXTRACTING);
        }

        // 按字段检索 + 合并去重（带 fileId 过滤避免跨文件污染）
        String filter = "fileId == '" + fileId + "'";
        List<Document> chunks = retrieveAndMerge(fields, filter);
        if (chunks.isEmpty()) {
            throw new BusinessException(ResultCode.VECTOR_RETRIEVE_ERROR, "未检索到相关文档切片");
        }

        // Retry 循环：LLM 调用 → JSON 解析 → Schema 校验 → 失败反馈重试
        ExtractionResult result = extractWithRetry(fields, chunks);
        if (!skipStatusWrites) {
            fileService.updateStatus(fileId, FileStatus.SUCCESS);
        }
        return result;
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
     */
    private ExtractionResult extractWithRetry(List<FormFieldVO> fields, List<Document> chunks) {
        String userPrompt = extractionPromptBuilder.buildUserPrompt(chunks);
        int totalCalls = maxAttempts + 1;
        FieldSchemaValidator.ValidationResult vr = null;
        String feedback = null;
        int used = 0;
        for (int i = 0; i < totalCalls; i++) {
            used = i + 1;
            String systemPrompt = extractionPromptBuilder.buildSystemPrompt(fields, feedback);
            Map<String, Object> raw = parseJson(callChatModel(systemPrompt, userPrompt));
            vr = fieldSchemaValidator.validate(raw, fields);
            if (!vr.hasErrors()) {
                return buildResult(vr.getCoerced(), List.of(), used);
            }
            feedback = extractionPromptBuilder.buildRetryFeedback(vr.getErrors());
        }
        return buildResult(vr.getCoerced(), vr.getErrors(), used);
    }

    /**
     * 组装 {@link ExtractionResult}。
     */
    private ExtractionResult buildResult(Map<String, Object> values, List<FieldError> errors, int used) {
        ExtractionResult r = new ExtractionResult();
        r.setValues(values);
        r.setErrors(errors);
        r.setAttemptsUsed(used);
        return r;
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
