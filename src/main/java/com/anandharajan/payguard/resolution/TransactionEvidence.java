package com.anandharajan.payguard.resolution;

import java.time.Instant;
import java.util.List;

public record TransactionEvidence(
        String captureId,
        long amountCents,
        String currency,
        Instant capturedAt,
        String mandateId,
        String status,
        long remainingRefundableCents,
        List<PriorRefund> priorRefunds,
        String evidenceVersion,
        Instant retrievedAt
) {
    public TransactionEvidence {
        priorRefunds = priorRefunds == null
                ? List.of()
                : List.copyOf(priorRefunds);
    }

    public TransactionEvidence(
            String captureId,
            long amountCents,
            String currency,
            Instant capturedAt,
            String mandateId,
            String status
    ) {
        this(
                captureId,
                amountCents,
                currency,
                capturedAt,
                mandateId,
                status,
                amountCents,
                List.of(),
                "fixture-v1",
                capturedAt
        );
    }

    public record PriorRefund(
            String refundId,
            long amountCents,
            String status,
            Instant occurredAt
    ) {}
}
