package com.astra.freyja.service.impl;

import com.astra.freyja.common.BizException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import reactor.core.publisher.Flux;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 轻量熔断装饰器：按连续失败次数熔断指定秒数，恢复窗口内放行并清零计数。
 * 不引入额外依赖，满足本模块存储的熔断配置。
 */
@Slf4j
public class CircuitBreakerChatModel implements ChatModel {

    private final ChatModel delegate;
    private final String name;
    private final boolean enabled;
    private final int threshold;
    private final long openNanos;

    private final AtomicInteger consecutiveFailures = new AtomicInteger();
    private volatile boolean open;
    private volatile long openedAt;

    public CircuitBreakerChatModel(ChatModel delegate, String name, Integer enableBreaker,
                                   Integer breakerThreshold, Integer breakerTimeout) {
        this.delegate = delegate;
        this.name = name;
        this.enabled = enableBreaker != null && enableBreaker == 1;
        this.threshold = breakerThreshold == null ? 10 : breakerThreshold;
        this.openNanos = TimeUnit.SECONDS.toNanos(breakerTimeout == null ? 30 : breakerTimeout);
    }

    private void checkOpen() {
        if (!enabled) {
            return;
        }
        if (open && System.nanoTime() - openedAt > openNanos) {
            open = false;
            consecutiveFailures.set(0);
        }
        if (open) {
            throw new BizException("AI 提供商「" + name + "」已熔断，请稍后重试");
        }
    }

    private void onSuccess() {
        consecutiveFailures.set(0);
    }

    private void onFailure() {
        if (!enabled) {
            return;
        }
        if (consecutiveFailures.incrementAndGet() >= threshold) {
            open = true;
            openedAt = System.nanoTime();
            log.warn("AI 提供商「{}」连续失败达到阈值 {}，触发熔断", name, threshold);
        }
    }

    @Override
    public ChatResponse call(Prompt prompt) {
        checkOpen();
        try {
            ChatResponse response = delegate.call(prompt);
            onSuccess();
            return response;
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            onFailure();
            throw e instanceof RuntimeException re ? re : new RuntimeException(e);
        }
    }

    @Override
    public Flux<ChatResponse> stream(Prompt prompt) {
        checkOpen();
        return delegate.stream(prompt)
                .doOnComplete(this::onSuccess)
                .doOnError(e -> onFailure());
    }

    @Override
    public ChatOptions getOptions() {
        return delegate.getOptions();
    }

    /**
     * Spring AI 2.0 已将 getOptions() 作为当前 API。
     * 保留旧方法仅用于兼容旧调用方，并统一转发到新的 Options 代理。
     */
    @Override
    @Deprecated(forRemoval = true)
    public ChatOptions getDefaultOptions() {
        return getOptions();
    }
}
