package com.astra.freyja.service;

import com.astra.freyja.config.MinioProperties;
import com.astra.freyja.dao.ResVideoMapper;
import com.astra.freyja.entity.ResVideo;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.minio.MinioClient;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ResVideoLegacyImporterTest {
    @Test void importsOnlyManagedLegacyUploadsOnceAndNeverResurrectsDeletedMaterials() {
        var jdbc = new JdbcTemplate(new DriverManagerDataSource("jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1", "sa", ""));
        jdbc.execute("CREATE TABLE drama_shot(id BIGINT PRIMARY KEY, drama_id BIGINT, ref_videos_json TEXT, deleted INT DEFAULT 0)");
        jdbc.execute("CREATE TABLE res_video(id BIGINT PRIMARY KEY, video_url VARCHAR(512), deleted INT DEFAULT 0)");
        String url = "http://127.0.0.1:9000/video-assets/reference-video/20261023/old.mp4";
        String json = "[{\"id\":\"old\",\"name\":\"Camera\",\"sourceType\":\"DIRECT_URL\",\"sourceVideoUrl\":\"" + url + "\"}]";
        jdbc.update("INSERT INTO drama_shot VALUES(1,100,?,0)", json);
        jdbc.update("INSERT INTO drama_shot VALUES(2,100,?,0)", json);
        jdbc.update("INSERT INTO drama_shot VALUES(3,100,?,0)", json.replace("127.0.0.1:9000", "external.test"));
        jdbc.update("INSERT INTO drama_shot VALUES(4,100,'invalid json',0)");
        var mapper = mock(ResVideoMapper.class);
        when(mapper.insert(any(ResVideo.class))).thenAnswer(invocation -> {
            ResVideo row = invocation.getArgument(0);
            assertEquals(100L, row.getDramaId()); assertEquals("SHOT_UPLOAD", row.getSourceType());
            assertNull(row.getDuration()); assertNull(row.getHasAudio());
            jdbc.update("INSERT INTO res_video VALUES (99,?,0)", row.getVideoUrl()); return 1;
        });
        var cleanup = new AssetMediaCleanupService(mock(MinioClient.class), new MinioProperties(), jdbc);
        var importer = new ResVideoLegacyImporter(jdbc, mapper, cleanup, new ObjectMapper());
        importer.importExisting(); importer.importExisting();
        jdbc.update("UPDATE res_video SET deleted=1"); importer.importExisting();
        verify(mapper, times(1)).insert(any(ResVideo.class));
        assertEquals(1, jdbc.queryForObject("SELECT deleted FROM res_video WHERE id=99", Integer.class));
    }
}
