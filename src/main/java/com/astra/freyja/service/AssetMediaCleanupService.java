package com.astra.freyja.service;

import com.astra.freyja.config.MinioProperties;
import io.minio.MinioClient;
import io.minio.RemoveObjectArgs;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.net.URI;
import java.sql.Types;
import java.util.*;

/** Delete only managed, unreferenced media after the owning transaction commits. */
@Slf4j
@Service
@RequiredArgsConstructor
public class AssetMediaCleanupService {
    private final MinioClient minio;
    private final MinioProperties storage;
    private final JdbcTemplate jdbc;
    private static final List<String> TABLES = List.of("res_character", "res_character_look", "res_scene",
            "res_prop", "res_keyframe", "res_video", "drama", "drama_shot", "drama_shot_video_take",
            "render_task", "media_process_task", "shot_video_reference_preflight", "ai_task",
            "shot_production_plan", "agent_preparation_job", "agent_step");

    public void deleteAfterCommit(String... urls) { deleteAfterCommit(Arrays.asList(urls)); }

    public void deleteUnreferencedNow(String url) {
        String key = managedObjectKey(url);
        if (key != null) deleteIfUnreferenced(key);
    }

    public void deleteAfterCommit(Collection<String> urls) {
        var keys = new LinkedHashSet<String>();
        for (String url : urls) { String key = managedObjectKey(url); if (key != null) keys.add(key); }
        if (keys.isEmpty()) return;
        Runnable cleanup = () -> keys.forEach(this::deleteIfUnreferenced);
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void afterCommit() { cleanup.run(); }
            });
        } else cleanup.run();
    }

    /** Exact configured origin and bucket; external URLs and foreign buckets are never deleted. */
    public String managedObjectKey(String url) {
        if (url == null || url.isBlank()) return null;
        try {
            URI target = URI.create(url);
            for (String endpoint : Arrays.asList(storage.getEndpoint(), storage.getExternalEndpoint())) {
                if (endpoint == null || endpoint.isBlank()) continue;
                URI base = URI.create(endpoint);
                if (!Objects.equals(base.getScheme(), target.getScheme()) || !Objects.equals(base.getHost(), target.getHost())
                        || base.getPort() != target.getPort() || target.getUserInfo() != null) continue;
                String prefix = base.getPath().replaceAll("/+$", "") + "/" + storage.getBucketName() + "/";
                if (!target.getPath().startsWith(prefix)) continue;
                String key = target.getPath().substring(prefix.length());
                if (key.isBlank() || Arrays.asList(key.split("/")).contains("..")) return null;
                return key;
            }
        } catch (IllegalArgumentException ignored) { }
        return null;
    }

    private void deleteIfUnreferenced(String key) {
        try {
            if (isReferenced(key)) { log.info("保留仍被引用的素材文件: {}", key); return; }
            minio.removeObject(RemoveObjectArgs.builder().bucket(storage.getBucketName()).object(key).build());
        } catch (Exception e) {
            // A failed reference check must retain the file. Never roll back already committed business data.
            log.warn("关联素材文件未清理，保留以便重试: {} ({})", key, e.getClass().getSimpleName());
        }
    }

    boolean isReferenced(String key) {
        return Boolean.TRUE.equals(jdbc.execute((ConnectionCallback<Boolean>) connection -> {
            var metadata = connection.getMetaData();
            for (String table : TABLES) {
                var columns = new ArrayList<String>();
                boolean deleted = false;
                try (var fields = metadata.getColumns(connection.getCatalog(), null, table, "%")) {
                    while (fields.next()) {
                        if (!table.equalsIgnoreCase(fields.getString("TABLE_NAME"))) continue;
                        String name = fields.getString("COLUMN_NAME");
                        deleted |= "deleted".equalsIgnoreCase(name);
                        int type = fields.getInt("DATA_TYPE");
                        if (Set.of(Types.CHAR, Types.VARCHAR, Types.LONGVARCHAR, Types.CLOB, Types.OTHER).contains(type))
                            columns.add(name);
                    }
                }
                if (columns.isEmpty()) continue;
                String where = String.join(" OR ", columns.stream().map(c -> "LOCATE(?, `" + c + "`) > 0").toList());
                String sql = "SELECT 1 FROM `" + table + "` WHERE " + (deleted ? "deleted = 0 AND " : "") + "(" + where + ") LIMIT 1";
                try (var statement = connection.prepareStatement(sql)) {
                    for (int i = 1; i <= columns.size(); i++) statement.setString(i, key);
                    try (var found = statement.executeQuery()) { if (found.next()) return true; }
                }
            }
            return false;
        }));
    }
}
