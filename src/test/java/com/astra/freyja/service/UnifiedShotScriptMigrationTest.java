package com.astra.freyja.service;

import com.astra.freyja.config.UnifiedShotScriptMigration;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import static org.junit.jupiter.api.Assertions.*;

class UnifiedShotScriptMigrationTest {
    @Test void startupArchivesHistoricalContentAndIsRepeatable() {
        var source = new DriverManagerDataSource("jdbc:h2:mem:script-migration-" + java.util.UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
        var jdbc = new JdbcTemplate(source);
        jdbc.execute("CREATE TABLE drama_shot (id BIGINT PRIMARY KEY, script_content LONGTEXT, dialogue_speaker VARCHAR(255), dialogue LONGTEXT, voiceover LONGTEXT, audio_url LONGTEXT)");
        jdbc.update("INSERT INTO drama_shot VALUES (1, ?, ?, ?, ?, ?)", "推门。", "林远", "你是谁？", "夜色渐深。", "old.wav");
        var migration = new UnifiedShotScriptMigration(source); migration.afterSingletonsInstantiated();
        String canonical = jdbc.queryForObject("SELECT script_content FROM drama_shot WHERE id=1", String.class);
        assertTrue(canonical.contains("推门。")); assertTrue(canonical.contains("林远：“你是谁？”")); assertTrue(canonical.contains("夜色渐深。"));
        assertEquals("old.wav", jdbc.queryForObject("SELECT audio_url FROM drama_shot_legacy_script_backup WHERE shot_id=1", String.class));
        assertNull(jdbc.queryForObject("SELECT dialogue FROM drama_shot WHERE id=1", String.class));
        migration.afterSingletonsInstantiated();
        assertEquals(canonical, jdbc.queryForObject("SELECT script_content FROM drama_shot WHERE id=1", String.class));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM drama_shot_legacy_script_backup", Integer.class));
    }
}
