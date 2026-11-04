package com.astra.freyja.auth;

import com.astra.freyja.common.BizException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class AuthServiceTest {
    AuthTestStore store;
    AuthService auth;
    @BeforeEach void setup() { store = new AuthTestStore(); auth = new AuthService(AuthTestStore.properties(), store.redis, store.credentials); }
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
        var restarted = new AuthService(AuthTestStore.properties(), store.redis, store.credentials);
        assertNotNull(restarted.authenticate("Bearer " + token));
        var changed = AuthTestStore.properties(); changed.setPassword("different-admin-password");
        assertNull(new AuthService(changed, store.redis, store.credentials).authenticate("Bearer " + token));
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
        assertThrows(IllegalArgumentException.class, () -> new AuthService(properties, store.redis, store.credentials));
        properties.setPassword("valid-long-password"); properties.setTokenTtlSeconds(0);
        assertThrows(IllegalArgumentException.class, () -> new AuthService(properties, store.redis, store.credentials));
    }

    @Test void passwordChangePersistsAndRevokesEverySessionAndPendingTicketAcrossInstances() {
        String first = login().token(); String second = login().token();
        var principal = auth.authenticate("Bearer " + first);
        String ticket = auth.createWebSocketTicket(principal);
        var otherInstance = new AuthService(AuthTestStore.properties(), store.redis, store.credentials);
        auth.changePassword(principal, "test-admin-password-2026", "my-permanent-password");
        assertNull(auth.authenticate("Bearer " + first));
        assertNull(otherInstance.authenticate("Bearer " + second));
        assertNull(otherInstance.consumeWebSocketTicket(ticket));
        assertThrows(BizException.class, this::login);
        var config = AuthTestStore.properties(); config.setPassword("");
        var restarted = new AuthService(config, store.redis, store.credentials);
        var changed = restarted.login("admin", "my-permanent-password", "new-ip");
        assertNotNull(restarted.authenticate("Bearer " + changed.token()));
        assertNotEquals("my-permanent-password", store.credentials.find("admin").passwordHash());
        assertTrue(store.credentials.find("admin").passwordHash().startsWith("pbkdf2-sha256$"));
    }

    @Test void badOldPasswordAndInvalidNewPasswordsDoNotChangeAnything() {
        var principal = auth.authenticate("Bearer " + login().token());
        assertEquals(400, assertThrows(BizException.class, () -> auth.changePassword(principal, "wrong", "my-permanent-password")).getCode());
        for (String invalid : new String[]{"short", "            ", "test-admin-password-2026", "x".repeat(257)})
            assertEquals(400, assertThrows(BizException.class, () -> auth.changePassword(principal, "test-admin-password-2026", invalid)).getCode());
        assertNotNull(auth.authenticate("Bearer " + login().token()));
        assertNull(store.credentials.find("admin"));
    }

    @Test void changingBackToAnOldPasswordNeverReactivatesAnOldToken() {
        String oldToken = login().token();
        auth.changePassword(auth.authenticate("Bearer " + oldToken), "test-admin-password-2026", "my-permanent-password");
        String newToken = auth.login("admin", "my-permanent-password", "new-ip").token();
        auth.changePassword(auth.authenticate("Bearer " + newToken), "my-permanent-password", "test-admin-password-2026");
        assertNull(auth.authenticate("Bearer " + oldToken));
        assertNull(auth.authenticate("Bearer " + newToken));
        assertNotNull(login());
    }

    @Test void passwordChangeIsRateLimitedEvenWhenTheCallerHasAValidToken() {
        var principal = auth.authenticate("Bearer " + login().token());
        for (int i = 0; i < 10; i++) assertThrows(BizException.class, () -> auth.changePassword(principal, "wrong", "my-permanent-password"));
        assertEquals(429, assertThrows(BizException.class, () -> auth.changePassword(principal, "test-admin-password-2026", "my-permanent-password")).getCode());
    }
}
