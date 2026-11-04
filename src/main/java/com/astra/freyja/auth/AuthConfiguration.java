package com.astra.freyja.auth;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.data.redis.core.StringRedisTemplate;
import tools.jackson.databind.ObjectMapper;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(AuthProperties.class)
public class AuthConfiguration {
    @Bean AuthService authService(AuthProperties properties, StringRedisTemplate redis) { return new AuthService(properties, redis); }

    @Bean FilterRegistrationBean<ApiAuthenticationFilter> apiAuthenticationFilter(AuthService auth, ObjectMapper json) {
        var registration = new FilterRegistrationBean<>(new ApiAuthenticationFilter(auth, json));
        registration.addUrlPatterns("/*");
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 10);
        return registration;
    }
}
