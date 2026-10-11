package com.anandharajan.payguard.resolution;

import org.springframework.stereotype.Component;

import java.time.Clock;
import java.util.Map;

@Component
public final class FixtureTransactionEvidenceProvider
        implements TransactionEvidenceProvider {

    private final Clock clock;

    public FixtureTransactionEvidenceProvider(Clock clock) {
        this.clock = clock;
    }

    @Override
    public TransactionEvidence find(String captureId) {
        if (captureId == null) {
            return null;
        }
        Map<String, Long> fixtures = Map.of(
                "CAPTURE-35", 3_500L,
                "CAPTURE-475", 47_500L,
                "CAPTURE-600", 60_000L
        );
        Long amount = fixtures.get(captureId);
        return amount == null
                ? null
                : new TransactionEvidence(
                        captureId,
                        amount,
                        "USD",
                        java.time.Instant.parse("2026-10-01T00:00:00Z"),
                        "payguard-demo-mandate",
                        "COMPLETED",
                        amount,
                        java.util.List.of(),
                        "fixture-v1-" + captureId,
                        clock.instant()
                );
    }
}
