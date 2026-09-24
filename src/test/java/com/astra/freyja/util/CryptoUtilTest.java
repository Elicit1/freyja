package com.astra.freyja.util;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class CryptoUtilTest {

    private CryptoUtil cryptoUtil;

    @BeforeEach
    void setUp() {
        cryptoUtil = new CryptoUtil("test-secret-key-1234567890-test");
        cryptoUtil.init();
    }

    @Test
    void testEncryptAndDecrypt() {
        String plaintext = "sk-1234567890abcdefghijklmnopqrstuvwxyz";
        String ciphertext = cryptoUtil.encrypt(plaintext);

        assertNotNull(ciphertext);
        assertNotEquals(plaintext, ciphertext);

        String decrypted = cryptoUtil.decrypt(ciphertext);
        assertEquals(plaintext, decrypted);
    }

    @Test
    void testEncryptNullOrEmpty() {
        assertNull(cryptoUtil.encrypt(null));
        assertNull(cryptoUtil.encrypt(""));
        assertNull(cryptoUtil.encrypt("   "));

        assertNull(cryptoUtil.decrypt(null));
        assertNull(cryptoUtil.decrypt(""));
        assertNull(cryptoUtil.decrypt("   "));
    }

    @Test
    void testMask() {
        assertEquals("", cryptoUtil.mask(null));
        assertEquals("", cryptoUtil.mask(""));
        assertEquals("****", cryptoUtil.mask("123"));
        assertEquals("****", cryptoUtil.mask("1234"));
        assertEquals("****5678", cryptoUtil.mask("12345678"));
        assertEquals("****wxyz", cryptoUtil.mask("sk-1234567890abcdefghijklmnopqrstuvwxyz"));
    }

    @Test
    void testFallbackDecryption() {
        // 创建一个用 dev key 加密的密文
        CryptoUtil devCrypto = new CryptoUtil("freyja-local-dev-key-please-change-me-0123456789");
        devCrypto.init();
        String cipher = devCrypto.encrypt("sk-test-secret-12345");

        // 另一个使用生产 key 的实例应该能够通过 fallback 自动平滑解密出来
        CryptoUtil prodCrypto = new CryptoUtil("freyja-production-secret-key-32bytes");
        prodCrypto.init();
        String decrypted = prodCrypto.decrypt(cipher);
        assertEquals("sk-test-secret-12345", decrypted);

        // 安全解密对损坏数据应返回 null
        assertNull(prodCrypto.safeDecrypt("invalid-base64-cipher"));
    }
}
