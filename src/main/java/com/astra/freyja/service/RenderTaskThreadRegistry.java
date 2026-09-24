package com.astra.freyja.service;

import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;

import java.util.concurrent.ConcurrentHashMap;

/**
 * 本地渲染执行线程注册中心 (统一管理关键帧生图、视频渲染与资产生图本地线程)。
 */
@Slf4j
public final class RenderTaskThreadRegistry {

    private static final ConcurrentHashMap<String, Thread> ACTIVE_THREADS = new ConcurrentHashMap<>();

    private RenderTaskThreadRegistry() {
    }

    /**
     * 注册当前正在执行的本地工作线程
     */
    public static void register(String taskId, Thread thread) {
        if (StringUtils.isNotBlank(taskId) && thread != null) {
            ACTIVE_THREADS.put(taskId, thread);
            log.debug("[RenderTaskThreadRegistry] 注册本地渲染线程: taskId={}, threadName={}", taskId, thread.getName());
        }
    }

    /**
     * 注销已结束的本地工作线程
     */
    public static void unregister(String taskId) {
        if (StringUtils.isNotBlank(taskId)) {
            ACTIVE_THREADS.remove(taskId);
            log.debug("[RenderTaskThreadRegistry] 注销本地渲染线程: taskId={}", taskId);
        }
    }

    /**
     * 中断本地正在等待或执行的渲染工作线程
     */
    public static boolean interrupt(String taskId) {
        if (StringUtils.isBlank(taskId)) return false;
        Thread thread = ACTIVE_THREADS.remove(taskId);
        if (thread != null && thread.isAlive()) {
            log.info("[RenderTaskThreadRegistry] 收到中断指令，正在打断本地渲染线程: taskId={}, threadName={}",
                    taskId, thread.getName());
            thread.interrupt();
            return true;
        }
        return false;
    }
}
