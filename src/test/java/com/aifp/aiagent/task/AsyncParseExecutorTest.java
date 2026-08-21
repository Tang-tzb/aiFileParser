package com.aifp.aiagent.task;

import com.aifp.aiagent.dto.ExtractionResult;
import com.aifp.aiagent.dto.TaskProgress;
import com.aifp.aiagent.rag.DocumentIngestionService;
import com.aifp.aiagent.rag.ProgressCallback;
import com.aifp.aiagent.service.FieldExtractorService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * {@link AsyncParseExecutor} 测试（离线）。
 * <p>
 * 直接调用 run()（无 Spring 代理，同步执行于测试线程），用 Answer 触发 ingest 回调，
 * 验证成功路径发布 0/50/80/100 四阶段、失败路径发布 FAILED。
 *
 * @author Tang_tzb
 */
@ExtendWith(MockitoExtension.class)
class AsyncParseExecutorTest {

    private static final Long FORM_ID = 1785508135L;
    private static final Long FILE_ID = 1785800001L;

    @Mock
    private DocumentIngestionService ingestionService;
    @Mock
    private FieldExtractorService fieldExtractorService;
    @Mock
    private ProgressPublisher progressPublisher;

    private AsyncParseExecutor executor;

    @BeforeEach
    void setUp() {
        executor = new AsyncParseExecutor(ingestionService, fieldExtractorService, progressPublisher);
    }

    /**
     * 成功：ingest 回调 PARSING→0%、VECTORING→50%，随后 80%、100% 携带 result。
     */
    @Test
    void run_success_publishesFourStagesInOrder() {
        stubIngestWithCallback();
        ExtractionResult result = buildResult();
        when(fieldExtractorService.extract(FORM_ID, FILE_ID)).thenReturn(result);

        executor.run("task-1", FORM_ID, FILE_ID);

        List<TaskProgress> published = capturePublished();
        assertThat(published).extracting(TaskProgress::getPercent)
                .containsExactly(0, 50, 80, 100);
        assertThat(published).extracting(TaskProgress::getStatus)
                .containsExactly("PARSING", "VECTORING", "EXTRACTING", "SUCCESS");
        assertThat(published.get(3).getResult()).isSameAs(result);
    }

    /**
     * 失败：ingest 抛异常 → 仅发布 FAILED。
     */
    @Test
    void run_ingestFailure_publishesFailed() {
        doThrow(new RuntimeException("解析失败"))
                .when(ingestionService).ingest(eq(FILE_ID), any(ProgressCallback.class));

        executor.run("task-2", FORM_ID, FILE_ID);

        List<TaskProgress> published = capturePublished();
        assertThat(published).hasSize(1);
        assertThat(published.get(0).getStatus()).isEqualTo("FAILED");
        assertThat(published.get(0).getPercent()).isEqualTo(-1);
        verify(fieldExtractorService, never()).extract(anyLong(), anyLong());
    }

    // ==================== 测试辅助 ====================

    /**
     * 用 Answer 在 ingest 调用时按序触发 PARSING/VECTORING 回调，模拟入库阶段流转。
     */
    private void stubIngestWithCallback() {
        doAnswer(invocation -> {
            ProgressCallback cb = invocation.getArgument(1);
            cb.onStageStart("PARSING");
            cb.onStageStart("VECTORING");
            return null;
        }).when(ingestionService).ingest(eq(FILE_ID), any(ProgressCallback.class));
    }

    private List<TaskProgress> capturePublished() {
        ArgumentCaptor<TaskProgress> captor = ArgumentCaptor.forClass(TaskProgress.class);
        verify(progressPublisher, atLeastOnce()).publish(captor.capture());
        return captor.getAllValues();
    }

    private ExtractionResult buildResult() {
        ExtractionResult r = new ExtractionResult();
        r.setValues(Map.of("projectName", "智慧校园"));
        r.setErrors(List.of());
        r.setAttemptsUsed(1);
        return r;
    }
}
