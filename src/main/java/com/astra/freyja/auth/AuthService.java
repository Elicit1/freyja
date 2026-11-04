package com.astra.freyja.auth;

import com.astra.freyja.common.BizException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;

/** Single administrator login; opaque sessions and one-use WebSocket tickets live in Redis. */
@Slf4j
public class AuthService {
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final String SESSION_PREFIX = "freyja:auth:session:";
    private static final String TICKET_PREFIX = "freyja:auth:ws:";
    private static final DefaultRedisScript<Long> LOGIN_LIMIT = new DefaultRedisScript<>(
            "local n = redis.call('INCR', KEYS[1]); if n == 1 then redis.call('EXPIRE', KEYS[1], 60) end; return n", Long.class);
    private final StringRedisTemplate redis;
    private final String username;
    private final byte[] passwordHash;
    private final String credentialVersion;
    private final long ttlSeconds;

    public AuthService(AuthProperties properties, StringRedisTemplate redis) {
        this.redis = redis;
        username = properties.getUsername();
        ttlSeconds = properties.getTokenTtlSeconds();
        if (username == null || username.isBlank() || username.length() > 64 || ttlSeconds < 60 || ttlSeconds > 604800)
            throw new IllegalArgumentException("登录用户名不能为空且最多 64 字符；Token 有效期须为 60～604800 秒");
        String password = properties.getPassword();
        if (password == null || password.isBlank()) {
            password = randomToken();
            log.warn("未配置 FREYJA_AUTH_PASSWORD，本次启动的临时管理员密码：{}（用户名：{}）", password, username);
        }
        if (password.length() < 12 || password.length() > 256)
            throw new IllegalArgumentException("FREYJA_AUTH_PASSWORD 必须为 12～256 字符");
        passwordHash = derive(password);
        credentialVersion = HexFormat.of().formatHex(passwordHash);
    }

    public LoginResult login(String suppliedUsername, String password, String remoteAddress) {
        Long attempts = redis.execute(LOGIN_LIMIT, List.of("freyja:auth:attempt:" + digest(remoteAddress)));
        if (attempts == null || attempts > 10) throw new BizException(429, "登录尝试过于频繁，请一分钟后重试");
        if (password == null || password.length() > 256 || suppliedUsername == null || suppliedUsername.length() > 64)
            throw new BizException(401, "用户名或密码错误");
        boolean passwordMatches = MessageDigest.isEqual(passwordHash, derive(password));
        if (!username.equals(suppliedUsername) || !passwordMatches) throw new BizException(401, "用户名或密码错误");
        String token = randomToken();
        redis.opsForValue().set(sessionKey(token), credentialVersion, Duration.ofSeconds(ttlSeconds));
        return new LoginResult(token, "Bearer", username, Instant.now().plusSeconds(ttlSeconds).toString());
    }

    public LoginPrincipal authenticate(String authorization) {
        if (authorization == null || !authorization.regionMatches(true, 0, "Bearer ", 0, 7)) return null;
        String token = authorization.substring(7);
        if (!validTokenFormat(token)) return null;
        String key = sessionKey(token);
        return isActive(key) ? new LoginPrincipal(username, key) : null;
    }

    public boolean isActive(String sessionKey) {
        return sessionKey != null && sessionKey.startsWith(SESSION_PREFIX)
                && credentialVersion.equals(redis.opsForValue().get(sessionKey));
    }

    public void logout(LoginPrincipal principal) { redis.delete(principal.sessionKey()); }

    public String createWebSocketTicket(LoginPrincipal principal) {
        String ticket = randomToken();
        redis.opsForValue().set(TICKET_PREFIX + digest(ticket), principal.sessionKey(), Duration.ofSeconds(30));
        return ticket;
    }

    public LoginPrincipal consumeWebSocketTicket(String ticket) {
        if (!validTokenFormat(ticket)) return null;
        String key = redis.opsForValue().getAndDelete(TICKET_PREFIX + digest(ticket));
        return isActive(key) ? new LoginPrincipal(username, key) : null;
    }

    private byte[] derive(String password) {
        PBEKeySpec spec = new PBEKeySpec(password.toCharArray(), ("freyja-admin:" + username).getBytes(StandardCharsets.UTF_8), 210000, 256);
        try { return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded(); }
        catch (java.security.GeneralSecurityException e) { throw new IllegalStateException("无法初始化登录密码校验", e); }
        finally { spec.clearPassword(); }
    }

    private static String sessionKey(String token) { return SESSION_PREFIX + digest(token); }
    private static boolean validTokenFormat(String token) {
        return token != null && token.length() == 43 && token.chars().allMatch(c ->
                c >= 'A' && c <= 'Z' || c >= 'a' && c <= 'z' || c >= '0' && c <= '9' || c == '-' || c == '_');
    }
    private static String digest(String text) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8))); }
        catch (java.security.NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
    private static String randomToken() {
        byte[] bytes = new byte[32]; RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    public record LoginResult(String token, String tokenType, String username, String expiresAt) {}
}
