package com.anandharajan.payguard.policy;

public record RefundDecision(
        Status status,
        String reason
) {
    public enum Status {
        APPROVED,
        PENDING_APPROVAL,
        DENIED
    }
}
