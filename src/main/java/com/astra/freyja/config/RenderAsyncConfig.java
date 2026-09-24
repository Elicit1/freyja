package com.astra.freyja.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * 渲染与生图异步任务专用线程池配置。
 */
@EnableAsync
@Configuration
public class RenderAsyncConfig {

    @Value("${freyja.render.executor.core-pool-size:4}")
    private int corePoolSize;

    @Value("${freyja.render.executor.max-pool-size:16}")
    private int maxPoolSize;

    @Value("${freyja.render.executor.queue-capacity:100}")
    private int queueCapacity;

    @Value("${freyja.render.executor.thread-name-prefix:render-async-}")
    private String threadNamePrefix;

    @Bean(name = {"renderAsyncExecutor", "comfyAsyncExecutor"})
    public Executor renderAsyncExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(corePoolSize);
        executor.setMaxPoolSize(maxPoolSize);
        executor.setQueueCapacity(queueCapacity);
        executor.setThreadNamePrefix(threadNamePrefix);
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(60);
        executor.initialize();
        return executor;
    }
}
