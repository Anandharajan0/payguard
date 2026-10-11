package com.anandharajan.payguard.paypal;

import com.anandharajan.payguard.policy.RefundState;
import tools.jackson.databind.JsonNode;

public final class PayPalRefundOutcomeMapper {

    public PayPalRefundOutcome map(JsonNode response) {
        if (response == null || !response.isObject()) {
            return PayPalRefundOutcome.create(
                    RefundState.RECONCILIATION_REQUIRED,
                    null,
                    null,
                    "PAYPAL_RESPONSE_INCOMPLETE"
            );
        }

        JsonNode refundIdNode = response.get("id");
        JsonNode statusNode = response.get("status");
        if (refundIdNode == null || !refundIdNode.isTextual()
                || statusNode == null || !statusNode.isTextual()) {
            return PayPalRefundOutcome.create(
                    RefundState.RECONCILIATION_REQUIRED,
                    null,
                    null,
                    "PAYPAL_RESPONSE_INCOMPLETE"
            );
        }

        String refundId = refundIdNode.asText();
        String status = statusNode.asText();
        if (refundId.isBlank() || status.isBlank()) {
            return PayPalRefundOutcome.create(
                    RefundState.RECONCILIATION_REQUIRED,
                    null,
                    status.isBlank() ? null : status,
                    "PAYPAL_RESPONSE_INCOMPLETE"
            );
        }

        return switch (status) {
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
                    "PAYPAL_REFUND_" + status
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
        return PayPalRefundOutcome.create(
                RefundState.RECONCILIATION_REQUIRED,
                null,
                null,
                "PAYPAL_HTTP_" + statusCode
        );
    }

    public PayPalRefundOutcome mapNotDispatched() {
        return PayPalRefundOutcome.create(
                RefundState.FAILED,
                null,
                null,
                "PAYPAL_TOKEN_UNAVAILABLE"
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

}
