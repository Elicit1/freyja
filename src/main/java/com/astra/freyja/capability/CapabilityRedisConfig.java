package com.astra.freyja.capability;

import com.astra.freyja.capability.service.CapabilityCatalogService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;

@Configuration(proxyBeanMethods = false)
public class CapabilityRedisConfig {
    @Bean RedisMessageListenerContainer capabilityNotifications(RedisConnectionFactory connection, CapabilityCatalogService catalog) {
        var container = new RedisMessageListenerContainer();
        container.setConnectionFactory(connection);
        container.addMessageListener((message, pattern) -> {
            try { catalog.current(); } catch (RuntimeException ignored) { /* Request-time MySQL checks also recover missed notifications. */ }
        }, new ChannelTopic(CapabilityCatalogService.EVENTS));
        return container;
    }
}
