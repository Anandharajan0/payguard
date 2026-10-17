package com.anandharajan.payguard.resolution;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class InMemoryCaptureProvenanceRepository
        implements CaptureProvenanceRepository {

    private final Map<String, CaptureProvenance> values =
            new ConcurrentHashMap<>();

    @Override
    public CaptureProvenance find(String captureId) {
        return values.get(captureId);
    }

    @Override
    public void register(CaptureProvenance provenance) {
        CaptureProvenance previous = values.putIfAbsent(
                provenance.captureId(), provenance
        );
        if (previous != null && !previous.equals(provenance)) {
            throw new IllegalStateException(
                    "Capture provenance cannot be overwritten"
            );
        }
    }
}
