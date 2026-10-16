package com.astra.freyja.service;

import com.astra.freyja.event.RenderQueueChangedEvent;
import com.astra.freyja.service.video.VideoRenderBatchService;
import com.astra.freyja.service.video.VideoRenderQueueWorker;
import org.junit.jupiter.api.Test;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class VideoRenderQueueWorkerTest {
    @Test void submissionDrainsSequentiallyAndStopsWhenQueueIsIdleOrBlocked() {
        var service = mock(VideoRenderBatchService.class);
        var coordinator = mock(RenderDispatchCoordinator.class);
        doAnswer(call -> { ((Runnable) call.getArgument(0)).run(); return null; })
                .when(coordinator).executeVideoQueue(any());
        when(service.runNext()).thenReturn(true, true, false);
        var events = mock(org.springframework.context.ApplicationEventPublisher.class);
        var worker = new VideoRenderQueueWorker(service, coordinator, Runnable::run, events);
        worker.onQueueChanged(new RenderQueueChangedEvent());
        verify(service, times(3)).runNext();
        verify(events).publishEvent(any(com.astra.freyja.event.RenderDispatchReleasedEvent.class));
        verifyNoMoreInteractions(service);
    }
}
