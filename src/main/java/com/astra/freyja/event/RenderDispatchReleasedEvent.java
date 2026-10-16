package com.astra.freyja.event;

/** Wakes admission waiters without scheduling an idle video consumer again. */
public record RenderDispatchReleasedEvent(boolean remote) {
    public RenderDispatchReleasedEvent() { this(false); }
}
