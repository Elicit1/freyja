package com.astra.freyja.auth;

import com.astra.freyja.common.BizException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

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
    private final AdminCredentialRepository credentials;
    private final AdminCredential bootstrapCredential;
    private final long ttlSeconds;

    public AuthService(AuthProperties properties, StringRedisTemplate redis, AdminCredentialRepository credentials) {
        this.redis = redis;
        this.credentials = credentials;
        username = properties.getUsername();
        ttlSeconds = properties.getTokenTtlSeconds();
        if (username == null || username.isBlank() || username.length() > 64 || ttlSeconds < 60 || ttlSeconds > 604800)
            throw new IllegalArgumentException("登录用户名不能为空且最多 64 字符；Token 有效期须为 60～604800 秒");
        if (credentials.find(username) != null) {
            bootstrapCredential = null;
            return;
        }
        String password = properties.getPassword();
        if (password == null || password.isBlank()) {
            password = randomToken();
            log.warn("未配置 FREYJA_AUTH_PASSWORD，本次启动的临时管理员密码：{}（用户名：{}）", password, username);
        }
        if (password.length() < 12 || password.length() > 256)
            throw new IllegalArgumentException("FREYJA_AUTH_PASSWORD 必须为 12～256 字符");
        String encoded = AdminPasswordHasher.encode(password, ("freyja-admin:" + username).getBytes(StandardCharsets.UTF_8));
        bootstrapCredential = new AdminCredential(username, encoded, AdminPasswordHasher.legacySessionVersion(encoded));
    }

    public LoginResult login(String suppliedUsername, String password, String remoteAddress) {
        checkAttempts("login:" + remoteAddress);
        if (password == null || password.length() > 256 || suppliedUsername == null || suppliedUsername.length() > 64)
            throw new BizException(401, "用户名或密码错误");
        AdminCredential credential = currentCredential();
        boolean passwordMatches = AdminPasswordHasher.matches(password, credential.passwordHash());
        if (!username.equals(suppliedUsername) || !passwordMatches) throw new BizException(401, "用户名或密码错误");
        String token = randomToken();
        redis.opsForValue().set(sessionKey(token), credential.sessionVersion(), Duration.ofSeconds(ttlSeconds));
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
        if (sessionKey == null || !sessionKey.startsWith(SESSION_PREFIX)) return false;
        String version = redis.opsForValue().get(sessionKey);
        return version != null && currentCredential().sessionVersion().equals(version);
    }

    public void changePassword(LoginPrincipal principal, String oldPassword, String newPassword) {
        if (!username.equals(principal.username()) || !isActive(principal.sessionKey()))
            throw new BizException(401, "登录已失效，请重新登录");
        checkAttempts("password-change:" + username);
        if (newPassword == null || newPassword.isBlank() || newPassword.length() < 12 || newPassword.length() > 256)
            throw new BizException(400, "新密码必须为 12～256 字符，且不能全为空白");
        AdminCredential stored = credentials.find(username);
        AdminCredential current = stored == null ? bootstrapCredential : stored;
        if (current == null) throw new IllegalStateException("管理员密码记录不存在");
        if (!AdminPasswordHasher.matches(oldPassword, current.passwordHash())) throw new BizException(400, "原密码不正确");
        if (oldPassword.equals(newPassword)) throw new BizException(400, "新密码不能与原密码相同");
        var replacement = new AdminCredential(username, AdminPasswordHasher.encode(newPassword), randomToken());
        boolean saved = stored == null ? credentials.insert(replacement) : credentials.replace(replacement, stored.sessionVersion());
        if (!saved) throw new BizException(409, "密码已发生变化，请重新登录后再试");
    }

    private AdminCredential currentCredential() {
        AdminCredential stored = credentials.find(username);
        if (stored != null) return stored;
        if (bootstrapCredential == null) throw new IllegalStateException("管理员密码记录不存在");
        return bootstrapCredential;
    }

    private void checkAttempts(String scope) {
        Long attempts = redis.execute(LOGIN_LIMIT, List.of("freyja:auth:attempt:" + digest(scope)));
        if (attempts == null || attempts > 10) throw new BizException(429, "操作过于频繁，请一分钟后重试");
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
