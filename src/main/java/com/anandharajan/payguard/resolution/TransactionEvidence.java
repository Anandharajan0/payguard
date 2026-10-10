package com.anandharajan.payguard.resolution;

import java.time.Instant;

public record TransactionEvidence(
        String captureId,
        long amountCents,
        String currency,
        Instant capturedAt,
        String mandateId,
        String status
) {}
