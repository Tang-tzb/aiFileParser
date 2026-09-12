package com.aifp.aiagent.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * 异步任务配置
 * <p>
 * 启用 {@link EnableAsync}，提供专用线程池：
 * <ul>
 *   <li>{@code parseExecutor}：供 {@link com.aifp.aiagent.task.AsyncParseExecutor} 执行
 *       文件解析→向量化→AI 抽取流水线；</li>
 *   <li>{@code assistantStreamExecutor}：供项目助手 SSE 流式对话（Phase K）——
 *       LLM 流式任务耗时 10~30s，与解析任务分池避免互占拖垮。</li>
 * </ul>
 * 拒绝策略均为 CallerRuns：队列满时由调用线程兜底执行，避免任务丢失。
 *
 * @author Tang_tzb
 */
@Configuration
@EnableAsync
public class AsyncConfig {

    @Bean("parseExecutor")
    public Executor parseExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(4);
        executor.setQueueCapacity(100);
        executor.setKeepAliveSeconds(60);
        executor.setThreadNamePrefix("parse-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(30);
        executor.initialize();
        return executor;
    }

    /**
     * 助手流式对话专用线程池（Phase K）：单次对话占用线程直至 LLM 流结束
     * （10~30s），与解析池隔离，防止双方互占。
     */
    @Bean("assistantStreamExecutor")
    public Executor assistantStreamExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(4);
        executor.setMaxPoolSize(8);
        executor.setQueueCapacity(64);
        executor.setKeepAliveSeconds(60);
        executor.setThreadNamePrefix("assistant-stream-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(30);
        executor.initialize();
        return executor;
    }
}
