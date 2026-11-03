package com.astra.freyja.config;

import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;

/** Archive and normalize historical rows before the application accepts work. Fail startup on migration failure. */
@Component
@RequiredArgsConstructor
public class UnifiedShotScriptMigration implements SmartInitializingSingleton {
    private final DataSource dataSource;

    @Override public void afterSingletonsInstantiated() {
        try {
            String sql = new ClassPathResource("sql/migrate_unified_shot_script.sql").getContentAsString(java.nio.charset.StandardCharsets.UTF_8)
                    .replace("START TRANSACTION;", "").replace("COMMIT;", "");
            var populator = new ResourceDatabasePopulator(new ByteArrayResource(sql.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
            new TransactionTemplate(new DataSourceTransactionManager(dataSource)).executeWithoutResult(tx -> populator.execute(dataSource));
        } catch (java.io.IOException error) {
            throw new IllegalStateException("无法读取统一剧本迁移脚本", error);
        }
    }
}
