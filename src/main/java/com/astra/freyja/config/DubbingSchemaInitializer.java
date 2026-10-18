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
    }
}
