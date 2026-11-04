package com.astra.freyja.auth;

import com.astra.freyja.common.BizException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class AuthServiceTest {
    AuthTestStore store;
    AuthService auth;
    @BeforeEach void setup() { store = new AuthTestStore(); auth = new AuthService(AuthTestStore.properties(), store.redis); }
    AuthService.LoginResult login() { return auth.login("admin", "test-admin-password-2026", "127.0.0.1"); }

    @Test void loginIssuesUniqueOpaqueTokensAndStoresOnlyHashes() {
        var first = login(); var second = login();
        assertNotEquals(first.token(), second.token());
        assertEquals("admin", auth.authenticate("bEaReR " + first.token()).username());
        assertEquals("Bearer", first.tokenType());
        assertEquals(43, first.token().length());
        assertTrue(store.entries.entrySet().stream().noneMatch(entry -> entry.getKey().contains(first.token()) || entry.getValue().value().equals(first.token())));
        assertNull(auth.authenticate(first.token()));
        assertNull(auth.authenticate("Bearer " + first.token() + "x"));
        assertNull(auth.authenticate("Bearer " + "x".repeat(43)));
    }

    @Test void badUsernameAndBadPasswordShareTheSameFailure() {
        var wrongUser = assertThrows(BizException.class, () -> auth.login("unknown", "test-admin-password-2026", "ip"));
        var wrongPassword = assertThrows(BizException.class, () -> auth.login("admin", "wrong", "ip"));
        assertEquals(401, wrongUser.getCode());
        assertEquals(wrongUser.getMessage(), wrongPassword.getMessage());
    }

    @Test void sessionExpiryAndLogoutInvalidateAccess() {
        var first = login(); var second = login();
        auth.logout(auth.authenticate("Bearer " + first.token()));
        assertNull(auth.authenticate("Bearer " + first.token()));
        assertNotNull(auth.authenticate("Bearer " + second.token()));
        store.time.addAndGet(60001);
        assertNull(auth.authenticate("Bearer " + second.token()));
    }

    @Test void credentialChangeInvalidatesOldSessionsButRestartWithSameCredentialsPreservesThem() {
        String token = login().token();
        var restarted = new AuthService(AuthTestStore.properties(), store.redis);
        assertNotNull(restarted.authenticate("Bearer " + token));
        var changed = AuthTestStore.properties(); changed.setPassword("different-admin-password");
        assertNull(new AuthService(changed, store.redis).authenticate("Bearer " + token));
    }

    @Test void loginRateLimitExpiresAndDoesNotTrustUserSuppliedUsername() {
        for (int i = 0; i < 10; i++) assertThrows(BizException.class, () -> auth.login("unknown", "wrong", "ip"));
        assertEquals(429, assertThrows(BizException.class, () -> auth.login("admin", "test-admin-password-2026", "ip")).getCode());
        store.time.addAndGet(60001);
        assertNotNull(auth.login("admin", "test-admin-password-2026", "ip"));
    }

    @Test void websocketTicketsAreSingleUseShortLivedAndBoundToTheLoginSession() {
        var principal = auth.authenticate("Bearer " + login().token());
        String ticket = auth.createWebSocketTicket(principal);
        assertEquals(principal, auth.consumeWebSocketTicket(ticket));
        assertNull(auth.consumeWebSocketTicket(ticket));
        String expired = auth.createWebSocketTicket(principal);
        store.time.addAndGet(30001);
        assertNull(auth.consumeWebSocketTicket(expired));
        String revoked = auth.createWebSocketTicket(principal);
        auth.logout(principal);
        assertNull(auth.consumeWebSocketTicket(revoked));
    }

    @Test void invalidConfigurationIsRejected() {
        var properties = AuthTestStore.properties(); properties.setPassword("short");
        assertThrows(IllegalArgumentException.class, () -> new AuthService(properties, store.redis));
        properties.setPassword("valid-long-password"); properties.setTokenTtlSeconds(0);
        assertThrows(IllegalArgumentException.class, () -> new AuthService(properties, store.redis));
    }
}
