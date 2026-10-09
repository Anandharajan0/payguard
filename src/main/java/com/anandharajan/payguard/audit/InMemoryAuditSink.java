package com.anandharajan.payguard.audit;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

@Component
public class InMemoryAuditSink implements AuditSink {

    private final List<AuditEvent> events = new CopyOnWriteArrayList<>();

    @Override
    public void record(AuditEvent event) {
        events.add(event);
    }

    public List<AuditEvent> events() {
        return List.copyOf(events);
    }
}
