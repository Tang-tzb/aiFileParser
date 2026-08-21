package com.aifp.aiagent.task;

import com.aifp.aiagent.dto.ExtractionResult;
import com.aifp.aiagent.dto.TaskProgress;
import com.aifp.aiagent.rag.DocumentIngestionService;
import com.aifp.aiagent.service.FieldExtractorService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

/**
 * 异步解析执行器
 * <p>
 * 在 {@code parseExecutor} 线程池中执行完整流水线：
 * 入库(回调驱动 0%/50%) → 80% AI抽取 → 100% 完成(携带 {@link ExtractionResult}) / FAILED。
 * 独立 bean，供 {@code ParseTaskServiceImpl} 注入调用以激活 {@link Async} 代理。
 * 失败仅发布 FAILED 进度，文件状态由底层 ingest/extract 自行流转。
 *
 * @author Tang_tzb
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AsyncParseExecutor {

    private final DocumentIngestionService ingestionService;
    private final FieldExtractorService fieldExtractorService;
    private final ProgressPublisher progressPublisher;

    /**
     * 执行异步流水线。{@link Async} 代理生效需跨 bean 调用。
     */
    @Async("parseExecutor")
    public void run(String taskId, Long formId, Long fileId) {
        TaskProgress base = new TaskProgress(taskId, fileId, formId,
                "PARSING", 0, "任务初始化", null);
        try {
            ingestWithProgress(base, fileId);
            progressPublisher.publish(base.with("EXTRACTING", 80, "AI抽取中", null));
            ExtractionResult result = fieldExtractorService.extract(formId, fileId);
            progressPublisher.publish(base.with("SUCCESS", 100, "完成", result));
            log.info("异步任务完成 taskId={}, formId={}, fileId={}", taskId, formId, fileId);
        } catch (Exception e) {
            log.error("异步任务失败 taskId={}: {}", taskId, e.getMessage(), e);
            progressPublisher.publish(base.with("FAILED", -1, "处理失败: " + e.getMessage(), null));
        }
    }

    /**
     * 入库并按阶段发布 0%/50% 进度。
     * extract 内部会再次幂等调用 ingest，因文件已 SUCCESS 而跳过，无重复处理。
     */
    private void ingestWithProgress(TaskProgress base, Long fileId) {
        ingestionService.ingest(fileId, stage -> {
            boolean parsing = "PARSING".equals(stage);
            int percent = parsing ? 0 : 50;
            String message = parsing ? "解析中" : "向量化中";
            progressPublisher.publish(base.with(stage, percent, message, null));
        });
    }
}
