package com.astra.freyja.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

@Configuration
public class VideoRenderQueueConfig {
    @Bean
    public org.springframework.data.redis.listener.RedisMessageListenerContainer renderQueueSignals(
            org.springframework.data.redis.connection.RedisConnectionFactory factory,
            com.astra.freyja.service.RenderQueueSignalBridge bridge) {
        var container = new org.springframework.data.redis.listener.RedisMessageListenerContainer();
        container.setConnectionFactory(factory);
        container.addMessageListener(bridge, new org.springframework.data.redis.listener.ChannelTopic(
                com.astra.freyja.service.RenderQueueSignalBridge.CHANNEL));
        return container;
    }

    @Bean
    public ThreadPoolTaskExecutor videoRenderExecutor() {
        var executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(1);
        executor.setMaxPoolSize(1);
        executor.setThreadNamePrefix("video-serial-");
        executor.setWaitForTasksToCompleteOnShutdown(false);
        return executor;
    }
}
