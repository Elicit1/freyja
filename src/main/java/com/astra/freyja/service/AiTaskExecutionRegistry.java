package com.astra.freyja.service;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** Tracks active AI calls so cancellation can interrupt the actual request thread. */
public final class AiTaskExecutionRegistry {
    private static final ConcurrentHashMap<Long, Set<Thread>> THREADS = new ConcurrentHashMap<>();
    private static final ThreadLocal<Deque<Long>> CURRENT = ThreadLocal.withInitial(ArrayDeque::new);

    private AiTaskExecutionRegistry() { }

    public static void register(Long taskId) {
        if (taskId == null) return;
        THREADS.computeIfAbsent(taskId, ignored -> ConcurrentHashMap.newKeySet()).add(Thread.currentThread());
        Deque<Long> stack = CURRENT.get();
        if (!taskId.equals(stack.peek())) stack.push(taskId);
    }

    public static Long currentTaskId() {
        return CURRENT.get().peek();
    }

    public static void unregister(Long taskId) {
        if (taskId == null) return;
        Set<Thread> threads = THREADS.get(taskId);
        if (threads != null) {
            threads.remove(Thread.currentThread());
            if (threads.isEmpty()) THREADS.remove(taskId, threads);
        }
        Deque<Long> stack = CURRENT.get();
        stack.removeFirstOccurrence(taskId);
        if (stack.isEmpty()) CURRENT.remove();
    }

    public static boolean interrupt(Long taskId) {
        Set<Thread> threads = THREADS.get(taskId);
        if (threads == null) return false;
        boolean interrupted = false;
        for (Thread thread : threads) {
            if (thread.isAlive() && thread != Thread.currentThread()) {
                thread.interrupt();
                interrupted = true;
            }
        }
        return interrupted;
    }
}
