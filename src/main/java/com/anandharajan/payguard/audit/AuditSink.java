package com.anandharajan.payguard.audit;

public interface AuditSink {
    void record(AuditEvent event);
}
