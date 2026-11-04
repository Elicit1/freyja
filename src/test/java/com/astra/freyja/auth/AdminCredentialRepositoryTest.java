package com.astra.freyja.auth;

import com.astra.freyja.dao.AdminCredentialMapper;
import com.astra.freyja.common.BizException;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.dao.DataAccessResourceFailureException;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class AdminCredentialRepositoryTest {
    JdbcDataSource source;
    AdminCredentialMapper mapper;
    MybatisAdminCredentialRepository credentials;
    AuthTestStore store;

    @BeforeEach void setup() throws Exception {
        source = new JdbcDataSource(); source.setURL("jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1");
        var factory = new SqlSessionFactoryBean(); factory.setDataSource(source);
        var configuration = new org.apache.ibatis.session.Configuration();
        configuration.setMapUnderscoreToCamelCase(true); configuration.addMapper(AdminCredentialMapper.class);
        factory.setConfiguration(configuration); factory.afterPropertiesSet();
        mapper = new SqlSessionTemplate(factory.getObject()).getMapper(AdminCredentialMapper.class);
        credentials = new MybatisAdminCredentialRepository(mapper, source);
        store = new AuthTestStore();
    }

    @Test void databasePasswordSurvivesServiceAndRedisRestartsAndOverridesTheBootstrapEnvironment() {
        var auth = new AuthService(AuthTestStore.properties(), store.redis, credentials);
        String token = auth.login("admin", "test-admin-password-2026", "ip").token();
        auth.changePassword(auth.authenticate("Bearer " + token), "test-admin-password-2026", "my-permanent-password");
        var record = credentials.find("admin");
        assertNotNull(record);
        assertNotEquals("my-permanent-password", record.passwordHash());
        assertTrue(AdminPasswordHasher.matches("my-permanent-password", record.passwordHash()));
        assertNull(auth.authenticate("Bearer " + token));

        var restartedRedis = new AuthTestStore();
        var config = AuthTestStore.properties(); config.setPassword("different-environment-password");
        var restarted = new AuthService(config, restartedRedis.redis, new MybatisAdminCredentialRepository(mapper, source));
        assertNotNull(restarted.login("admin", "my-permanent-password", "ip"));
        assertThrows(BizException.class, () -> restarted.login("admin", "test-admin-password-2026", "ip"));
        assertThrows(BizException.class, () -> restarted.login("admin", "different-environment-password", "ip"));
    }

    @Test void concurrentWritesUseTheExpectedVersionAndHashesHaveIndependentRandomSalts() {
        String password = "my-permanent-password";
        var original = new AdminCredential("admin", AdminPasswordHasher.encode(password), "version1");
        assertTrue(credentials.insert(original));
        assertFalse(credentials.insert(original));
        var changed = new AdminCredential("admin", AdminPasswordHasher.encode(password), "version2");
        assertNotEquals(original.passwordHash(), changed.passwordHash());
        assertTrue(credentials.replace(changed, "version1"));
        assertFalse(credentials.replace(original, "version1"));
        assertEquals(changed, credentials.find("admin"));
    }

    @Test void failedDatabaseWriteNeverClaimsSuccessOrChangesTheWorkingPassword() {
        var unavailable = spy(credentials);
        doThrow(new DataAccessResourceFailureException("database offline")).when(unavailable).insert(any());
        var auth = new AuthService(AuthTestStore.properties(), store.redis, unavailable);
        String token = auth.login("admin", "test-admin-password-2026", "ip").token();
        assertThrows(DataAccessResourceFailureException.class,
                () -> auth.changePassword(auth.authenticate("Bearer " + token), "test-admin-password-2026", "my-permanent-password"));
        assertNull(credentials.find("admin"));
        assertNotNull(auth.authenticate("Bearer " + token));
        assertNotNull(auth.login("admin", "test-admin-password-2026", "ip"));
    }
}
