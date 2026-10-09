package com.anandharajan.payguard.refund;

import java.time.Instant;

public record RefundHttpRequest(
        String captureId,
        Long amountCents,
        String currency,
        Instant transactionCreatedAt
) {}
