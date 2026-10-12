package com.astra.freyja.agent.model;

/** Recoverable compaction failure, distinct from a business/tool failure. */
public class SummaryBlockedException extends RuntimeException {
    private final String code;
    public SummaryBlockedException(String code, String message) { super(message); this.code = code; }
    public String code() { return code; }
}
