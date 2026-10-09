package com.anandharajan.payguard.policy;

public enum RefundState {
    APPROVED,
    PENDING_APPROVAL,
    DENIED,
    IN_FLIGHT,
    EXECUTED,
    FAILED,
    RECONCILIATION_REQUIRED
}
