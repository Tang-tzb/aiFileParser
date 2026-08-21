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
 * 启用 {@link EnableAsync}，提供专用线程池 {@code parseExecutor} 供
 * {@link com.aifp.aiagent.task.AsyncParseExecutor} 执行文件解析→向量化→AI 抽取流水线。
 * 拒绝策略 CallerRuns：队列满时由调用线程兜底执行，避免任务丢失。
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
}
