package com.anandharajan.payguard.paypal;

import com.anandharajan.payguard.policy.RefundState;

public final class PayPalRefundOutcome {

    private final RefundState state;
    private final String refundId;
    private final String paypalStatus;
    private final String error;

    private PayPalRefundOutcome(
            RefundState state,
            String refundId,
            String paypalStatus,
            String error
    ) {
        this.state = state;
        this.refundId = refundId;
        this.paypalStatus = paypalStatus;
        this.error = error;
    }

    static PayPalRefundOutcome create(
            RefundState state,
            String refundId,
            String paypalStatus,
            String error
    ) {
        if (state == null) {
            throw new IllegalArgumentException("Outcome state is required");
        }
        if (state == RefundState.EXECUTED
                && (refundId == null || refundId.isBlank()
                || paypalStatus == null
                || !"COMPLETED".equalsIgnoreCase(paypalStatus)
                || error != null)) {
            throw new IllegalArgumentException(
                    "Only a completed PayPal refund with an ID can be executed"
            );
        }
        return new PayPalRefundOutcome(state, refundId, paypalStatus, error);
    }

    public RefundState state() {
        return state;
    }

    public String refundId() {
        return refundId;
    }

    public String paypalStatus() {
        return paypalStatus;
    }

    public String error() {
        return error;
    }
}
