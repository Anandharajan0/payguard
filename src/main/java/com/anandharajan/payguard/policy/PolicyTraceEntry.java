package com.anandharajan.payguard.policy;

public record PolicyTraceEntry(
        String rule,
        boolean passed,
        String explanation
) {}
