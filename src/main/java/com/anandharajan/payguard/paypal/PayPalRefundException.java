package com.anandharajan.payguard.paypal;

public final class PayPalRefundException extends RuntimeException {

    private final PayPalRefundOutcome outcome;

    public PayPalRefundException(PayPalRefundOutcome outcome) {
        super(outcome.error());
        this.outcome = outcome;
    }

    public PayPalRefundOutcome outcome() {
        return outcome;
    }
}
