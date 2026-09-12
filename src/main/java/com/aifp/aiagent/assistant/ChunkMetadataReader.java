package com.aifp.aiagent.assistant;

import org.springframework.ai.document.Document;
import org.springframework.stereotype.Component;

/**
 * RAG 命中切片 metadata 读取器（Phase 5 约定：metadata 值统一 String 化）
 * <p>
 * 将 {@link Document} metadata 中的来源键（fileId/fileName/pageStart）安全解析为
 * 类型化投影，供 AssistantPromptBuilder（Prompt 渲染）与 ProjectAssistantServiceImpl
 * （references FILE 组组装）共用；解析失败（格式非法）一律置 null，不抛异常、
 * 不猜测（硬约束 ⑦：来源不可靠时宁可缺失）。
 *
 * @author Tang_tzb
 */
@Component
public class ChunkMetadataReader {

    /**
     * 读取切片来源投影。
     *
     * @param doc RAG 命中切片（null 安全，返回全 null 投影）
     * @return 来源投影（字段可空）
     */
    public ChunkSource read(Document doc) {
        if (doc == null || doc.getMetadata() == null) {
            return new ChunkSource(null, null, null);
        }
        return new ChunkSource(readLong(doc.getMetadata().get("fileId")),
                readString(doc.getMetadata().get("fileName")),
                readPage(doc.getMetadata()));
    }

    /**
     * 解析来源页码：优先 pageStart（Phase 12 语义），回退 page（旧切片路径）。
     */
    private Integer readPage(java.util.Map<String, Object> metadata) {
        Object value = metadata.get("pageStart");
        if (value == null) {
            value = metadata.get("page");
        }
        return readInt(value);
    }

    // ==================== 内部方法 ====================

    /**
     * 宽松解析 Long：兼容未 String 化的 Number 值与 String 值，非法返回 null。
     */
    private Long readLong(Object value) {
        if (value instanceof Number n) {
            return n.longValue();
        }
        if (value instanceof String s && !s.isBlank()) {
            try {
                return Long.parseLong(s.trim());
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    /**
     * 宽松解析 Integer：兼容 Number 与 String 值，非法返回 null。
     */
    private Integer readInt(Object value) {
        if (value instanceof Number n) {
            return n.intValue();
        }
        if (value instanceof String s && !s.isBlank()) {
            try {
                return Integer.parseInt(s.trim());
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    /**
     * 宽松读取字符串：非 String 一律返回 null（metadata 均为 String 化约定值）。
     */
    private String readString(Object value) {
        return value instanceof String s && !s.isBlank() ? s : null;
    }

    /**
     * 单个 RAG 命中切片的来源投影
     *
     * @param fileId   来源文件 ID（metadata 缺失或格式非法时为 null）
     * @param fileName 来源文件名（缺失时为 null）
     * @param page     来源起始页码（pageStart，旧切片路径回退 page；缺失/非法时为 null）
     */
    public record ChunkSource(Long fileId, String fileName, Integer page) {
    }
}
