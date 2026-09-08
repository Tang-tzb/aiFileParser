package com.aifp.aiagent.parser.pdf.chunk;

/**
 * 结构块种子（阶段 12，包私有中间模型）：StructuralChunker 的产出单元，
 * 经 HybridSemanticChunker 统一编号并补 fileId/fileName 后构建 {@link Chunk}。
 * <p>
 * 语义块种子（content/chunkType）由 {@link SemanticChunker} 降级产出时
 * 仅填前两字段，其余字段由 StructuralChunker 合并补齐。
 *
 * @param content    块文本
 * @param chunkType  块类型
 * @param pageStart  起始页（1-based）
 * @param pageEnd    结束页（1-based）
 * @param titlePath  标题路径
 * @param bbox       可选 bbox 字符串（"x,y,width,height"）
 * @param confidence 可选最小置信度
 * @param sourceType 可选来源去重串
 * @author Tang_tzb
 */
record ChunkSeed(String content, ChunkType chunkType, Integer pageStart, Integer pageEnd,
                 String titlePath, String bbox, Float confidence, String sourceType) {

    /**
     * 仅 content + chunkType 的种子（SemanticChunker 降级产出，待 Structural 合并）。
     */
    static ChunkSeed of(String content, ChunkType chunkType) {
        return new ChunkSeed(content, chunkType, null, null, null, null, null, null);
    }

    /**
     * 合并元数据：保留本种子的 content/chunkType，页范围/标题路径/可选聚合取自模板。
     */
    ChunkSeed withMeta(ChunkSeed template) {
        return new ChunkSeed(content, chunkType, template.pageStart, template.pageEnd,
                template.titlePath, template.bbox, template.confidence, template.sourceType);
    }
}
