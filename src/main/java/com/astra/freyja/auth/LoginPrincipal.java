package com.astra.freyja.auth;

public record LoginPrincipal(String username, String sessionKey) {
    public static final String ATTRIBUTE = "freyja.loginPrincipal";
}
