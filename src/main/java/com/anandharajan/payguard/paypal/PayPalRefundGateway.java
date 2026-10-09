package com.anandharajan.payguard.paypal;

import tools.jackson.databind.JsonNode;

public interface PayPalRefundGateway {
    JsonNode refund(
            String captureId,
            long amountCents,
            String currency,
            String requestId
    );
}
