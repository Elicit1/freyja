package com.astra.freyja.service;

import com.astra.freyja.dao.ResVideoMapper;
import com.astra.freyja.entity.ResVideo;
import com.astra.freyja.util.ShotVideoReferenceValidator;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** Import older managed shot uploads without downloading or guessing metadata. */
@Slf4j
@Component
@RequiredArgsConstructor
public class ResVideoLegacyImporter implements ApplicationRunner {
    private final JdbcTemplate jdbc;
    private final ResVideoMapper videos;
    private final AssetMediaCleanupService storage;
    private final ObjectMapper json;

    @Override public void run(ApplicationArguments arguments) {
        try { importExisting(); }
        catch (Exception e) { log.warn("旧参考视频入库未完成，下次启动会重试，请检查视频素材表迁移: {}", e.getClass().getSimpleName()); }
    }

    public void importExisting() {
        long cursor = 0;
        while (true) {
            var rows = jdbc.queryForList("SELECT id, drama_id, ref_videos_json FROM drama_shot WHERE deleted=0 AND id>? AND ref_videos_json IS NOT NULL ORDER BY id LIMIT 100", cursor);
            if (rows.isEmpty()) return;
            for (var row : rows) {
                long shotId = ((Number) row.get("id")).longValue(); cursor = shotId;
                try {
                    for (var ref : ShotVideoReferenceValidator.parse((String) row.get("ref_videos_json"), json)) {
                        if (!"DIRECT_URL".equals(ref.getSourceType())) continue;
                        String key = storage.managedObjectKey(ref.getSourceVideoUrl());
                        if (key == null || !key.startsWith("reference-video/")) continue;
                        // Include deleted rows so deleting a material is never undone on restart.
                        if (jdbc.queryForObject("SELECT COUNT(*) FROM res_video WHERE video_url=?", Long.class, ref.getSourceVideoUrl()) > 0) continue;
                        var video = new ResVideo();
                        video.setDramaId(((Number) row.get("drama_id")).longValue()); video.setShotId(shotId);
                        String name = ref.getName() == null || ref.getName().isBlank() ? "参考视频" : ref.getName();
                        video.setName(name.substring(0, Math.min(200, name.length())));
                        video.setDescription(ref.getReferenceDescription()); video.setVideoUrl(ref.getSourceVideoUrl());
                        video.setSourceType("SHOT_UPLOAD"); video.setStatus(1); video.setSortOrder(0);
                        videos.insert(video);
                    }
                } catch (Exception e) { log.warn("旧参考视频暂未入库: shotId={}, reason={}", shotId, e.getClass().getSimpleName()); }
            }
        }
    }
}
