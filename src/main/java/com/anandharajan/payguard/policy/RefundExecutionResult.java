package com.anandharajan.payguard.policy;

public record RefundExecutionResult(
        RefundDecision decision,
        String refundId,
        String paypalStatus
) {}
