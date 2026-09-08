package com.aifp.aiagent.parser.pdf.chunk;

import com.knuddels.jtokkit.Encodings;
import com.knuddels.jtokkit.api.Encoding;
import com.knuddels.jtokkit.api.EncodingRegistry;
import com.knuddels.jtokkit.api.EncodingType;
import com.knuddels.jtokkit.api.IntArrayList;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * token 计数器（阶段 12）：JTokkit {@code cl100k_base} 封装，
 * 与旧 DocumentChunker 使用同一分词器（GPT 系近似计数口径一致，
 * 对 Qwen 等模型为近似计数）。Encoding 实例线程安全，单例复用。
 *
 * @author Tang_tzb
 */
@Slf4j
@Component
public class TokenCounter {

    private static final EncodingType ENCODING_TYPE = EncodingType.CL100K_BASE;

    private Encoding encoding;

    @PostConstruct
    void init() {
        EncodingRegistry registry = Encodings.newDefaultEncodingRegistry();
        this.encoding = registry.getEncoding(ENCODING_TYPE);
        log.info("TokenCounter 初始化 encoding={}", ENCODING_TYPE);
    }

    /**
     * 统计文本 token 数；null/空文本返回 0。
     *
     * @param text 待计数文本（可 null）
     * @return token 数
     */
    public int count(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        return encoding.countTokens(text);
    }

    /**
     * token 窗口切分（阶段 12 TOKEN 降级层专用）：按 windowSize 大小滑动切分文本，
     * 相邻窗口重叠 overlap token（stride = windowSize − overlap）。
     * 切片数推导与旧 DocumentChunker 一致：最小 k 使 k*stride + windowSize ≥ total，
     * 切片数 = k + 1（整数向上取整避免浮点误差）。
     *
     * @param text       待切分文本（可 null）
     * @param windowSize 窗口大小（token）
     * @param overlap    相邻窗口重叠（token）
     * @return 切片文本列表；null/空文本返回空列表；不超窗口返回单块
     */
    public List<String> window(String text, int windowSize, int overlap) {
        if (text == null || text.isEmpty()) {
            return List.of();
        }
        IntArrayList tokens = encoding.encode(text);
        int total = tokens.size();
        if (total <= windowSize) {
            return List.of(text);
        }
        int stride = Math.max(1, windowSize - overlap);
        List<String> parts = new ArrayList<>();
        int start = 0;
        while (start < total) {
            int end = Math.min(start + windowSize, total);
            IntArrayList sub = new IntArrayList(end - start);
            for (int i = start; i < end; i++) {
                sub.add(tokens.get(i));
            }
            parts.add(encoding.decode(sub));
            if (end >= total) {
                break;
            }
            start += stride;
        }
        return parts;
    }
}
