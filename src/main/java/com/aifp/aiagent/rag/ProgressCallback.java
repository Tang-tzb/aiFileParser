package com.aifp.aiagent.rag;

/**
 * 文档入库阶段进度回调
 * <p>
 * 由 {@link DocumentIngestionService#ingest(Long, ProgressCallback)} 在 PARSING/VECTORING
 * 阶段起始时触发，供上层（如 {@code AsyncParseExecutor}）发布 0%/50% 进度。
 * 定义于 rag 包使入库服务自洽，依赖方向为 上层→rag（不反向）。
 *
 * @author Tang_tzb
 */
@FunctionalInterface
public interface ProgressCallback {

    /**
     * 阶段起始回调。
     *
     * @param stageCode 阶段码：{@code "PARSING"} / {@code "VECTORING"}
     */
    void onStageStart(String stageCode);
}
