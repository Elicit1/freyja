package com.astra.freyja.event;

/** A committed submission, cancellation or explicit recovery can make work runnable. */
public record RenderQueueChangedEvent(boolean remote) {
    public RenderQueueChangedEvent() { this(false); }
}
