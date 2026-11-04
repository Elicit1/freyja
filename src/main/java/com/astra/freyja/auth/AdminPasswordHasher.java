package com.astra.freyja.auth;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;

final class AdminPasswordHasher {
    private static final int ITERATIONS = 210000;
    private static final SecureRandom RANDOM = new SecureRandom();

    static String encode(String password) {
        byte[] salt = new byte[32]; RANDOM.nextBytes(salt);
        return encode(password, salt);
    }

    static String encode(String password, byte[] salt) {
        return "pbkdf2-sha256$" + ITERATIONS + "$" + Base64.getEncoder().encodeToString(salt)
                + "$" + Base64.getEncoder().encodeToString(derive(password, salt));
    }

    static boolean matches(String password, String encoded) {
        if (password == null || password.length() > 256) return false;
        String[] parts = parts(encoded);
        return MessageDigest.isEqual(Base64.getDecoder().decode(parts[3]), derive(password, Base64.getDecoder().decode(parts[2])));
    }

    static String legacySessionVersion(String encoded) {
        return HexFormat.of().formatHex(Base64.getDecoder().decode(parts(encoded)[3]));
    }

    private static String[] parts(String encoded) {
        String[] parts = encoded.split("\\$");
        if (parts.length != 4 || !parts[0].equals("pbkdf2-sha256") || !parts[1].equals(Integer.toString(ITERATIONS)))
            throw new IllegalStateException("管理员密码记录格式无效");
        return parts;
    }

    private static byte[] derive(String password, byte[] salt) {
        PBEKeySpec spec = new PBEKeySpec(password.toCharArray(), salt, ITERATIONS, 256);
        try { return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded(); }
        catch (java.security.GeneralSecurityException e) { throw new IllegalStateException("无法校验登录密码", e); }
        finally { spec.clearPassword(); }
    }
}
