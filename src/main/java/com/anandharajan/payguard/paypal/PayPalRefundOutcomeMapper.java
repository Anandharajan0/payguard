package com.anandharajan.payguard.paypal;

import com.anandharajan.payguard.policy.RefundState;
import tools.jackson.databind.JsonNode;

import java.util.Locale;

public final class PayPalRefundOutcomeMapper {

    public PayPalRefundOutcome map(JsonNode response) {
        if (response == null) {
            return PayPalRefundOutcome.create(
                    RefundState.RECONCILIATION_REQUIRED,
                    null,
                    null,
                    "PAYPAL_RESPONSE_INCOMPLETE"
            );
        }

        String refundId = text(response, "id");
        String status = text(response, "status");
        if (refundId == null || status == null) {
            return PayPalRefundOutcome.create(
                    RefundState.RECONCILIATION_REQUIRED,
                    refundId,
                    status,
                    "PAYPAL_RESPONSE_INCOMPLETE"
            );
        }

        return switch (status.toUpperCase(Locale.ROOT)) {
            case "COMPLETED" -> PayPalRefundOutcome.create(
                    RefundState.EXECUTED,
                    refundId,
                    status,
                    null
            );
            case "FAILED", "CANCELLED" -> PayPalRefundOutcome.create(
                    RefundState.FAILED,
                    refundId,
                    status,
                    "PAYPAL_REFUND_" + status.toUpperCase(Locale.ROOT)
            );
            case "PENDING" -> PayPalRefundOutcome.create(
                    RefundState.RECONCILIATION_REQUIRED,
                    refundId,
                    status,
                    "PAYPAL_REFUND_PENDING"
            );
            default -> PayPalRefundOutcome.create(
                    RefundState.RECONCILIATION_REQUIRED,
                    refundId,
                    status,
                    "PAYPAL_REFUND_STATUS_UNKNOWN"
            );
        };
    }

    public PayPalRefundOutcome mapHttpFailure(int statusCode) {
        RefundState state = statusCode >= 400 && statusCode < 500
                ? RefundState.FAILED
                : RefundState.RECONCILIATION_REQUIRED;
        return PayPalRefundOutcome.create(
                state,
                null,
                null,
                "PAYPAL_HTTP_" + statusCode
        );
    }

    public PayPalRefundOutcome mapAmbiguousTransportFailure() {
        return PayPalRefundOutcome.create(
                RefundState.RECONCILIATION_REQUIRED,
                null,
                null,
                "PAYPAL_OUTCOME_AMBIGUOUS"
        );
    }

    private static String text(JsonNode response, String field) {
        String value = response.path(field).asText(null);
        return value == null || value.isBlank() ? null : value;
    }
}
