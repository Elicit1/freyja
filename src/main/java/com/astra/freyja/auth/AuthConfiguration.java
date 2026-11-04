package com.astra.freyja.auth;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.data.redis.core.StringRedisTemplate;
import tools.jackson.databind.ObjectMapper;
import com.astra.freyja.dao.AdminCredentialMapper;
import javax.sql.DataSource;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(AuthProperties.class)
public class AuthConfiguration {
    @Bean AdminCredentialRepository adminCredentialRepository(AdminCredentialMapper mapper, DataSource source) {
        return new MybatisAdminCredentialRepository(mapper, source);
    }

    @Bean AuthService authService(AuthProperties properties, StringRedisTemplate redis, AdminCredentialRepository credentials) {
        return new AuthService(properties, redis, credentials);
    }

    @Bean FilterRegistrationBean<ApiAuthenticationFilter> apiAuthenticationFilter(AuthService auth, ObjectMapper json) {
        var registration = new FilterRegistrationBean<>(new ApiAuthenticationFilter(auth, json));
        registration.addUrlPatterns("/*");
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 10);
        return registration;
    }
}
