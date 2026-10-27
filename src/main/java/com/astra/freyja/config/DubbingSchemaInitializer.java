package com.astra.freyja.config;

import lombok.RequiredArgsConstructor;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.stereotype.Component;
import javax.sql.DataSource;

@Component
@Order(-100)
@RequiredArgsConstructor
public class DubbingSchemaInitializer implements ApplicationRunner {
    private final DataSource dataSource;
    @Override public void run(ApplicationArguments args) {
        new ResourceDatabasePopulator(new ClassPathResource("sql/dubbing-center.sql")).execute(dataSource);
        new ResourceDatabasePopulator(new ClassPathResource("sql/asset-audio-library.sql")).execute(dataSource);
        // MySQL 8 does not support ADD COLUMN IF NOT EXISTS; inspect before the additive upgrade.
        try (var connection = dataSource.getConnection()) {
            try (var columns = connection.getMetaData().getColumns(connection.getCatalog(), null,
                    "character_voice_audio", "model_request_json")) {
                if (columns.next()) return;
            }
            try (var statement = connection.createStatement()) {
                statement.executeUpdate("ALTER TABLE character_voice_audio ADD COLUMN model_request_json JSON NULL");
            }
        } catch (java.sql.SQLException e) {
            throw new IllegalStateException("配音请求 JSON 字段升级失败", e);
        }
    }
}
