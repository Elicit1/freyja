package com.astra.freyja.service.video;

import com.astra.freyja.event.RenderQueueChangedEvent;
import com.astra.freyja.service.RenderDispatchCoordinator;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;
import java.util.concurrent.Executor;

/** Submissions wake the consumer; each completed item immediately dispatches the next. */
@Slf4j
@Component
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(name="freyja.video-queue.enabled",havingValue="true",matchIfMissing=true)
public class VideoRenderQueueWorker {
    private final VideoRenderBatchService service;
    private final RenderDispatchCoordinator coordinator;
    private final Executor executor;
    private final org.springframework.context.ApplicationEventPublisher events;

    public VideoRenderQueueWorker(VideoRenderBatchService service, RenderDispatchCoordinator coordinator,
                                  @Qualifier("videoRenderExecutor") Executor executor,
                                  org.springframework.context.ApplicationEventPublisher events) {
        this.service = service;
        this.coordinator = coordinator;
        this.executor = executor;
        this.events = events;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void recover() { wake(); }

    @TransactionalEventListener(fallbackExecution = true)
    public void onQueueChanged(RenderQueueChangedEvent event) { wake(); }

    private void wake() {
        executor.execute(() -> {
            try {
                coordinator.executeVideoQueue(() -> {
                    while (service.runNext()) { /* generation and archive finish before advancing */ }
                });
            } catch (Exception e) {
                log.warn("视频串行队列执行失败，请检查数据库与队列状态: {}", e.getMessage());
            } finally {
                events.publishEvent(new com.astra.freyja.event.RenderDispatchReleasedEvent());
            }
        });
    }
}
