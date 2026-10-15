package com.astra.freyja.service.video;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import javax.sql.DataSource;

/** One synchronous consumer. A session lock prevents another application instance running it. */
@Slf4j
@Component
@RequiredArgsConstructor
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(name="freyja.video-queue.enabled",havingValue="true",matchIfMissing=true)
public class VideoRenderQueueWorker {
    private final DataSource dataSource;
    private final VideoRenderBatchService service;
    private boolean warned;

    @Scheduled(scheduler="videoRenderScheduler", fixedDelayString="${freyja.video-queue.scan-delay-ms:1000}", initialDelayString="${freyja.video-queue.initial-delay-ms:3000}")
    public void scan() {
        try(var connection=dataSource.getConnection()) {
            String lock="freyja-video-"+VideoBatchPreparation.hash(String.valueOf(connection.getCatalog())).substring(0,20);
            try(var statement=connection.prepareStatement("SELECT GET_LOCK(?,0)")) {
                statement.setString(1,lock);
                try(var result=statement.executeQuery()){if(!result.next()||result.getInt(1)!=1)return;}
            }
            try { service.runNext();warned=false; }
            finally { try(var statement=connection.prepareStatement("SELECT RELEASE_LOCK(?)")){statement.setString(1,lock);statement.execute();} }
        }catch(Exception e){if(!warned)log.warn("视频串行队列未就绪，请确认 video-render-batch.sql 已执行: {}",e.getMessage());warned=true;}
    }
}
