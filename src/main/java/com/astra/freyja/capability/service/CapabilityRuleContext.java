package com.astra.freyja.capability.service;

/** Trusted invocation scope; async jobs must persist a copy before leaving this scope. */
public final class CapabilityRuleContext implements AutoCloseable {
    private static final ThreadLocal<String> RULES = new ThreadLocal<>();
    private final String previous;
    private CapabilityRuleContext(String rules) { previous = RULES.get(); RULES.set(rules); }
    public static CapabilityRuleContext open(String rules) { return new CapabilityRuleContext(rules); }
    public static String current() { return RULES.get(); }
    @Override public void close() { if (previous == null) RULES.remove(); else RULES.set(previous); }
}
