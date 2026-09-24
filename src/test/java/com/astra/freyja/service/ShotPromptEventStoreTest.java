package com.astra.freyja.service;

import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.ListOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ShotPromptEventStoreTest {
    @Test
    void parallelWorkersBroadcastInJournalOrder() throws Exception {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        ListOperations<String, String> lists = mock(ListOperations.class);
        when(redis.opsForList()).thenReturn(lists);
        AtomicLong sequence = new AtomicLong();
        CountDownLatch firstStored = new CountDownLatch(1);
        when(lists.rightPush(anyString(), anyString())).thenAnswer(call -> {
            long value = sequence.incrementAndGet();
            if (value == 1) firstStored.countDown();
            return value;
        });
        ShotPromptEventStore store = new ShotPromptEventStore(redis, new ObjectMapper());
        ConcurrentLinkedQueue<Long> received = new ConcurrentLinkedQueue<>();
        store.addListener(event -> {
            if (event.seq() == 1) {
                try { Thread.sleep(80); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            }
            received.add(event.seq());
        });
        Thread workerOne = Thread.ofVirtual().start(() -> store.append("task-1", "channel_chunk", "planner"));
        org.junit.jupiter.api.Assertions.assertTrue(firstStored.await(2, TimeUnit.SECONDS));
        Thread workerTwo = Thread.ofVirtual().start(() -> store.append("task-1", "channel_chunk", "worker"));
        workerOne.join();
        workerTwo.join();
        assertEquals(List.of(1L, 2L), List.copyOf(received));
    }

    @Test
    void replayStartsAfterLastReceivedSequence() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        ListOperations<String, String> lists = mock(ListOperations.class);
        when(redis.opsForList()).thenReturn(lists);
        List<String> journal = new ArrayList<>();
        when(lists.rightPush(anyString(), anyString())).thenAnswer(call -> {
            journal.add(call.getArgument(1));
            return (long) journal.size();
        });
        when(lists.range(anyString(), anyLong(), anyLong())).thenAnswer(call -> {
            long from = call.getArgument(1);
            return new ArrayList<>(journal.subList((int) Math.min(from, journal.size()), journal.size()));
        });

        ShotPromptEventStore store = new ShotPromptEventStore(redis, new ObjectMapper());
        List<ShotPromptEventStore.Event> live = new ArrayList<>();
        store.addListener(live::add);
        store.append("2032095628944588801", "stage", "正在加载 Skill");
        store.append("2032095628944588801", "chunk", "第一段");
        store.append("2032095628944588801", "done", "[DONE]");

        assertEquals(List.of(1L, 2L, 3L), live.stream().map(ShotPromptEventStore.Event::seq).toList());
        List<ShotPromptEventStore.Event> resumed = store.after("2032095628944588801", 1);
        assertEquals(List.of("chunk", "done"), resumed.stream().map(ShotPromptEventStore.Event::type).toList());
        assertEquals(List.of(2L, 3L), resumed.stream().map(ShotPromptEventStore.Event::seq).toList());
    }
}
