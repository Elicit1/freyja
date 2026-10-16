package com.astra.freyja.config;

import com.astra.freyja.service.mediaexport.ShotVideoExportWorker;
import org.springframework.context.annotation.*;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.listener.*;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

@Configuration
public class VideoExportConfig {
    @Bean public ThreadPoolTaskExecutor videoExportExecutor(){var executor=new ThreadPoolTaskExecutor();executor.setCorePoolSize(1);executor.setMaxPoolSize(1);executor.setThreadNamePrefix("video-export-");executor.setWaitForTasksToCompleteOnShutdown(false);return executor;}
    @Bean public RedisMessageListenerContainer videoExportSignals(RedisConnectionFactory factory,ShotVideoExportWorker worker){var container=new RedisMessageListenerContainer();container.setConnectionFactory(factory);container.addMessageListener(worker,new ChannelTopic(ShotVideoExportWorker.CHANNEL));return container;}
}
