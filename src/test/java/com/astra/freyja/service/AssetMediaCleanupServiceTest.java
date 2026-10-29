package com.astra.freyja.service;

import com.astra.freyja.config.MinioProperties;
import io.minio.MinioClient;
import io.minio.RemoveObjectArgs;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AssetMediaCleanupServiceTest {
    MinioClient minio;
    JdbcTemplate jdbc;
    AssetMediaCleanupService cleanup;
    TransactionTemplate transaction;
    final String url = "http://127.0.0.1:9000/video-assets/scene/test.png";

    @BeforeEach void setup() {
        var source = new DriverManagerDataSource("jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1", "sa", "");
        jdbc = new JdbcTemplate(source);
        jdbc.execute("CREATE TABLE res_scene(id BIGINT PRIMARY KEY, cover_url VARCHAR(512), deleted INT DEFAULT 0)");
        jdbc.execute("CREATE TABLE drama_shot(id BIGINT PRIMARY KEY, ref_images_json TEXT, deleted INT DEFAULT 0)");
        minio = mock(MinioClient.class);
        cleanup = new AssetMediaCleanupService(minio, new MinioProperties(), jdbc);
        transaction = new TransactionTemplate(new DataSourceTransactionManager(source));
    }

    @Test void onlyDeletesOwnBucketAndExactOrigin() throws Exception {
        assertNull(cleanup.managedObjectKey("http://127.0.0.1:9000.evil.test/video-assets/scene/test.png"));
        assertNull(cleanup.managedObjectKey("http://127.0.0.1:9000/other/scene/test.png"));
        assertNull(cleanup.managedObjectKey("http://127.0.0.1:9000/video-assets/../other/test.png"));
        cleanup.deleteAfterCommit("https://external.test/image.png", url, url);
        var argument = org.mockito.ArgumentCaptor.forClass(RemoveObjectArgs.class);
        verify(minio, times(1)).removeObject(argument.capture());
        assertEquals("scene/test.png", argument.getValue().object());
    }

    @Test void deletesOnlyAfterCommitAndIgnoresDeletedOwner() throws Exception {
        jdbc.update("INSERT INTO res_scene VALUES (1,?,0)", url);
        transaction.executeWithoutResult(status -> {
            jdbc.update("UPDATE res_scene SET deleted=1 WHERE id=1");
            cleanup.deleteAfterCommit(url);
            verifyNoInteractions(minio);
        });
        verify(minio).removeObject(any(RemoveObjectArgs.class));
    }

    @Test void rollbackRetainsFileAndOwner() {
        jdbc.update("INSERT INTO res_scene VALUES (1,?,0)", url);
        transaction.executeWithoutResult(status -> {
            jdbc.update("UPDATE res_scene SET deleted=1 WHERE id=1");
            cleanup.deleteAfterCommit(url); status.setRollbackOnly();
        });
        verifyNoInteractions(minio);
        assertEquals(0, jdbc.queryForObject("SELECT deleted FROM res_scene WHERE id=1", Integer.class));
    }

    @Test void sharedImageAndShotSnapshotRetainPhysicalFile() {
        jdbc.update("INSERT INTO res_scene VALUES (1,?,0)", url);
        cleanup.deleteAfterCommit(url);
        jdbc.update("UPDATE res_scene SET deleted=1");
        jdbc.update("INSERT INTO drama_shot VALUES (2,?,0)", "[{\"imageUrl\":\"" + url + "\"}]");
        cleanup.deleteAfterCommit(url);
        verifyNoInteractions(minio);
    }

    @Test void failureToInspectReferencesNeverDeletes() {
        var broken = new AssetMediaCleanupService(minio, new MinioProperties(), mock(JdbcTemplate.class, invocation -> { throw new IllegalStateException("offline"); }));
        assertDoesNotThrow(() -> broken.deleteAfterCommit(url));
        verifyNoInteractions(minio);
    }

    @Test void queuedVideoSnapshotRetainsOldSceneImage() throws Exception {
        jdbc.execute("CREATE TABLE video_render_batch_item (id BIGINT, snapshot_json CLOB)");
        jdbc.update("INSERT INTO video_render_batch_item VALUES (1,?)", "{\"imageUrl\":\"" + url + "\"}");
        cleanup.deleteUnreferencedNow(url);
        verifyNoInteractions(minio);
    }
}
