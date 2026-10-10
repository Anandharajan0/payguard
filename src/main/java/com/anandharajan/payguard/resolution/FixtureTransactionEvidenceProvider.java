package com.anandharajan.payguard.resolution;

import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
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
                        Instant.now(clock).minusSeconds(60),
                        "payguard-demo-mandate",
                        "COMPLETED"
                );
    }
}
