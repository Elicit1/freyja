package com.astra.freyja.auth;

import com.astra.freyja.dao.AdminCredentialMapper;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

import javax.sql.DataSource;

public final class MybatisAdminCredentialRepository implements AdminCredentialRepository {
    private final AdminCredentialMapper mapper;

    public MybatisAdminCredentialRepository(AdminCredentialMapper mapper, DataSource source) {
        this.mapper = mapper;
        new ResourceDatabasePopulator(new ClassPathResource("sql/admin-credential.sql")).execute(source);
    }

    @Override public AdminCredential find(String username) { return mapper.find(username); }
    @Override public boolean insert(AdminCredential credential) {
        try { return mapper.insert(credential) == 1; }
        catch (DuplicateKeyException conflict) { return false; }
    }
    @Override public boolean replace(AdminCredential credential, String expectedVersion) {
        return mapper.replace(credential, expectedVersion) == 1;
    }
}
