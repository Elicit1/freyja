package com.astra.freyja.auth;

/** Private authentication state; never returned by system configuration or business APIs. */
public record AdminCredential(String username, String passwordHash, String sessionVersion) {
    @Override public String toString() { return "AdminCredential[username=" + username + "]"; }
}
