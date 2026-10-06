package com.anandharajan.payguard.policy;

import java.time.Instant;

public record RefundRequest(
        String captureId,
        Long amountCents,
        String currency,
        Instant transactionCreatedAt
) {}
