package com.astra.freyja.util;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;

/**
 * AES-GCM 加解密工具，用于 API Key 等敏感字段落库加密。
 * 密钥从配置项 freyja.crypto.key 派生（SHA-256），密文格式为 base64(iv + ciphertext)。
 * 支持历史默认密钥自动兼容与安全回退解密。
 */
@Slf4j
@Component
public class CryptoUtil {

    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final int IV_LENGTH = 12;
    private static final int TAG_LENGTH_BITS = 128;

    /** 历史默认密钥（用于开发/生产环境无缝平滑迁移与回退兼容） */
    private static final List<String> KNOWN_LEGACY_KEYS = List.of(
            "freyja-local-dev-key-please-change-me-0123456789",
            "freyja-production-secret-key-32bytes"
    );

    private final String key;
    private final SecureRandom secureRandom = new SecureRandom();
    private volatile SecretKeySpec secretKey;
    private final List<SecretKeySpec> fallbackKeys = new ArrayList<>();

    public CryptoUtil(@Value("${freyja.crypto.key}") String key) {
        this.key = key;
    }

    @PostConstruct
    void init() {
        try {
            this.secretKey = deriveKey(key);
            for (String legacyKey : KNOWN_LEGACY_KEYS) {
                if (!legacyKey.equals(key)) {
                    fallbackKeys.add(deriveKey(legacyKey));
                }
            }
        } catch (Exception e) {
            throw new IllegalStateException("初始化 AES 密钥失败", e);
        }
    }

    private SecretKeySpec deriveKey(String rawKey) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(rawKey.getBytes(StandardCharsets.UTF_8));
            return new SecretKeySpec(Arrays.copyOf(digest, 16), "AES");
        } catch (Exception e) {
            throw new IllegalStateException("AES 密钥派生失败", e);
        }
    }

    /** 加密明文，返回 base64(iv + ciphertext)；明文为空返回 null。 */
    public String encrypt(String plaintext) {
        if (!StringUtils.hasText(plaintext)) {
            return null;
        }
        try {
            byte[] iv = new byte[IV_LENGTH];
            secureRandom.nextBytes(iv);
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, secretKey, new GCMParameterSpec(TAG_LENGTH_BITS, iv));
            byte[] encrypted = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            byte[] combined = new byte[iv.length + encrypted.length];
            System.arraycopy(iv, 0, combined, 0, iv.length);
            System.arraycopy(encrypted, 0, combined, iv.length, encrypted.length);
            return Base64.getEncoder().encodeToString(combined);
        } catch (Exception e) {
            throw new IllegalStateException("AES 加密失败", e);
        }
    }

    /** 解密 base64(iv + ciphertext)；密文为空返回 null。支持备用密钥平滑回退。 */
    public String decrypt(String ciphertext) {
        if (!StringUtils.hasText(ciphertext)) {
            return null;
        }
        byte[] combined;
        try {
            combined = Base64.getDecoder().decode(ciphertext);
            if (combined.length <= IV_LENGTH) {
                throw new IllegalArgumentException("密文长度非法");
            }
        } catch (Exception e) {
            throw new IllegalStateException("AES 密文 Base64 解码失败", e);
        }

        byte[] iv = Arrays.copyOfRange(combined, 0, IV_LENGTH);
        byte[] encrypted = Arrays.copyOfRange(combined, IV_LENGTH, combined.length);

        // 1. 优先尝试主密钥解密
        try {
            return doDecrypt(secretKey, iv, encrypted);
        } catch (Exception primaryEx) {
            // 2. 主密钥解密失败，尝试备用历史密钥
            for (SecretKeySpec fallbackKey : fallbackKeys) {
                try {
                    String decrypted = doDecrypt(fallbackKey, iv, encrypted);
                    log.warn("检测到旧密钥密文，已通过兼容备用密钥平滑解密成功。建议在管理后台重新保存以迁移至最新密钥。");
                    return decrypted;
                } catch (Exception ignored) {
                    // 继续尝试下一个备用密钥
                }
            }
            throw new IllegalStateException("AES 解密失败", primaryEx);
        }
    }

    /** 安全解密：失败返回 null，不抛出异常中断上层业务。 */
    public String safeDecrypt(String ciphertext) {
        try {
            return decrypt(ciphertext);
        } catch (Exception e) {
            log.warn("AES 密文解密失败，返回 null: {}", e.getMessage());
            return null;
        }
    }

    private String doDecrypt(SecretKeySpec keySpec, byte[] iv, byte[] encrypted) throws Exception {
        Cipher cipher = Cipher.getInstance(TRANSFORMATION);
        cipher.init(Cipher.DECRYPT_MODE, keySpec, new GCMParameterSpec(TAG_LENGTH_BITS, iv));
        return new String(cipher.doFinal(encrypted), StandardCharsets.UTF_8);
    }

    /** 掩码：仅保留末 4 位，如 sk-****1234；空串返回空串。 */
    public String mask(String plaintext) {
        if (!StringUtils.hasText(plaintext)) {
            return "";
        }
        if (plaintext.length() <= 4) {
            return "****";
        }
        return "****" + plaintext.substring(plaintext.length() - 4);
    }
}