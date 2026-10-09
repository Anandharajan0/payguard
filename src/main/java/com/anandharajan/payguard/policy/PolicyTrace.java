package com.anandharajan.payguard.policy;

import java.util.List;

public record PolicyTrace(List<PolicyTraceEntry> entries) {
    public PolicyTrace {
        entries = List.copyOf(entries);
    }
}
