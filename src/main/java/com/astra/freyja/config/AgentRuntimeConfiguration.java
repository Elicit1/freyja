package com.astra.freyja.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Configuration
@EnableScheduling
public class AgentRuntimeConfiguration {
    @Bean(destroyMethod = "shutdownNow")
    public ExecutorService agentModelExecutor() {
        return Executors.newThreadPerTaskExecutor(Thread.ofVirtual().name("agent-model-", 0).factory());
    }
    @Bean(destroyMethod = "shutdownNow")
    public ExecutorService agentExecutionExecutor() {
        return Executors.newThreadPerTaskExecutor(Thread.ofVirtual().name("agent-run-", 0).factory());
    }
}
