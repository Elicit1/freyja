package com.astra.freyja.service;

import com.astra.freyja.dao.*;
import com.astra.freyja.entity.AiTask;
import com.astra.freyja.service.impl.AiTaskServiceImpl;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.concurrent.ConcurrentHashMap;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

final class AiTaskServiceTestSupport {
    static ScriptDecomposeDraftStore drafts() {
        var store = mock(ScriptDecomposeDraftStore.class);
        var rows = new ConcurrentHashMap<Long, AiTask>();
        lenient().doAnswer(call -> { AiTask row = call.getArgument(0); rows.put(row.getId(), row); return null; }).when(store).saveTask(any());
        lenient().when(store.getTask(anyLong())).thenAnswer(call -> rows.get(call.getArgument(0)));
        lenient().when(store.listTasks()).thenAnswer(call -> java.util.List.copyOf(rows.values()));
        return store;
    }
    static AiTaskServiceImpl create(AiTaskMapper mapper) {
        var service = new AiTaskServiceImpl(mapper, new com.fasterxml.jackson.databind.ObjectMapper(),
                mock(DramaMapper.class), mock(DramaEpisodeMapper.class));
        configure(service, drafts());
        return service;
    }
    static void configure(AiTaskServiceImpl service, ScriptDecomposeDraftStore store) {
        ReflectionTestUtils.setField(service, "draftStore", store);
        ReflectionTestUtils.setField(service, "aiEvents", mock(AiTaskEventStore.class));
        service.setEventPublisher(mock(org.springframework.context.ApplicationEventPublisher.class));
    }
}
