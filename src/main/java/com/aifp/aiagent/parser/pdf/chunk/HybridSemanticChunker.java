package com.aifp.aiagent.parser.pdf.chunk;

import com.aifp.aiagent.parser.pdf.ast.DocumentAst;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 混合语义切片门面（阶段 12，对应《PDF解析改造方案》阶段 12）：
 * 编排 {@link StructuralChunker}（结构边界 + 原子块）与
 * {@link SemanticChunker}（超长降级），并对全文档块统一编号
 * chunkIndex/totalChunks，补齐 fileId/fileName 构建最终 {@link Chunk}。
 * <p>
 * 只消费 DocumentAst（阶段 12 契约）；切片优先级链：标题 &gt; 章节 &gt;
 * 段落 &gt; Table &gt; KeyValue &gt; List（预留）&gt; Sentence &gt; Token Length。
 *
 * @author Tang_tzb
 */
@Slf4j
@Component
public class HybridSemanticChunker {

    private final StructuralChunker structuralChunker;

    public HybridSemanticChunker(StructuralChunker structuralChunker) {
        this.structuralChunker = structuralChunker;
    }

    /**
     * 混合语义切片入口：结构切块 → 全局编号 → 构建 Chunk。
     *
     * @param ast    文档 AST（清洗后；可 null）
     * @param fileId 文件记录 ID（可 null，序列化为空串）
     * @return 切片列表；null AST / 无内容返回空列表
     */
    public List<Chunk> chunk(DocumentAst ast, Long fileId) {
        if (ast == null) {
            return List.of();
        }
        List<ChunkSeed> seeds = structuralChunker.chunk(ast);
        if (seeds.isEmpty()) {
            return List.of();
        }
        int total = seeds.size();
        String fileIdStr = fileId == null ? "" : String.valueOf(fileId);
        List<Chunk> chunks = new ArrayList<>(total);
        for (int i = 0; i < total; i++) {
            chunks.add(buildChunk(seeds.get(i), i, total, fileIdStr, ast.getFileName()));
        }
        log.info("混合语义切片完成 fileName={}, chunks={}", ast.getFileName(), total);
        return chunks;
    }

    /**
     * 块种子 → 最终 Chunk：补 fileId/fileName/全局序号；
     * titlePath null 归一为空串（无标题文档契约）。
     */
    private Chunk buildChunk(ChunkSeed seed, int index, int total, String fileId, String fileName) {
        return Chunk.builder()
                .content(seed.content())
                .fileId(fileId)
                .fileName(fileName)
                .pageStart(seed.pageStart())
                .pageEnd(seed.pageEnd())
                .titlePath(seed.titlePath() == null ? "" : seed.titlePath())
                .chunkType(seed.chunkType())
                .chunkIndex(index)
                .totalChunks(total)
                .bbox(seed.bbox())
                .confidence(seed.confidence())
                .sourceType(seed.sourceType())
                .build();
    }
}
