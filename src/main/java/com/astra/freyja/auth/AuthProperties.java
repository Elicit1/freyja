package com.astra.freyja.auth;

import lombok.Data;
import lombok.ToString;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Data
@ConfigurationProperties("freyja.auth")
public class AuthProperties {
    private String username = "admin";
    @ToString.Exclude
    private String password = "";
    private long tokenTtlSeconds = 43200;
}
