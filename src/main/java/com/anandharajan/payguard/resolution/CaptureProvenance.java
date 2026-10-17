package com.anandharajan.payguard.resolution;

import java.time.Instant;

public record CaptureProvenance(
        String captureId,
        String mandateId,
        long amountCents,
        String currency,
        String orderId,
        Instant registeredAt,
        boolean noExternalRefundsConfirmed
) {
    public CaptureProvenance {
        if (captureId == null || captureId.isBlank()
                || mandateId == null || mandateId.isBlank()
                || amountCents <= 0
                || currency == null || !currency.matches("[A-Z]{3}")
                || orderId == null || orderId.isBlank()
                || registeredAt == null) {
            throw new IllegalArgumentException(
                    "Capture provenance is incomplete"
            );
        }
    }
}
