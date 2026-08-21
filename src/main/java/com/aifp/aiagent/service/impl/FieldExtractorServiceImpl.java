package com.aifp.aiagent.service.impl;

import com.aifp.aiagent.common.ResultCode;
import com.aifp.aiagent.dto.FormFieldVO;
import com.aifp.aiagent.dto.FormVO;
import com.aifp.aiagent.entity.enums.FileStatus;
import com.aifp.aiagent.exception.BusinessException;
import com.aifp.aiagent.rag.DocumentIngestionService;
import com.aifp.aiagent.rag.ExtractionPromptBuilder;
import com.aifp.aiagent.rag.FieldQueryGenerator;
import com.aifp.aiagent.rag.VectorStoreService;
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
 * 流程：ingest(幂等) → 读字段 → EXTRACTING → 按字段检索+合并去重 chunks →
 * 动态 Prompt → 单次 ChatModel 调用 → 解析 JSON → Map → SUCCESS。
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
    private final ChatModel chatModel;
    private final ObjectMapper objectMapper;

    @Value("${rag.retrieve.topK:5}")
    private int topK;

    @Override
    public Map<String, Object> extract(Long formId, Long fileId) {
        // 幂等：确保文档已入 Milvus
        ingestionService.ingest(fileId);
        List<FormFieldVO> fields = loadFields(formId);
        fileService.updateStatus(fileId, FileStatus.EXTRACTING);

        // 按字段检索 + 合并去重（带 fileId 过滤避免跨文件污染）
        String filter = "fileId == '" + fileId + "'";
        List<Document> chunks = retrieveAndMerge(fields, filter);
        if (chunks.isEmpty()) {
            throw new BusinessException(ResultCode.VECTOR_RETRIEVE_ERROR, "未检索到相关文档切片");
        }

        // 动态 Prompt → 单次 LLM 调用 → JSON 解析
        String systemPrompt = extractionPromptBuilder.buildSystemPrompt(fields);
        String userPrompt = extractionPromptBuilder.buildUserPrompt(chunks);
        String json = callChatModel(systemPrompt, userPrompt);
        Map<String, Object> result = parseJson(json);

        fileService.updateStatus(fileId, FileStatus.SUCCESS);
        log.info("字段抽取完成 formId={}, fileId={}, fields={}", formId, fileId, result.size());
        return result;
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
