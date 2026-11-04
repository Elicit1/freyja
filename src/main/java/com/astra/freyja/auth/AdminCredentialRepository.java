package com.astra.freyja.auth;

public interface AdminCredentialRepository {
    AdminCredential find(String username);
    boolean insert(AdminCredential credential);
    boolean replace(AdminCredential credential, String expectedVersion);
}
