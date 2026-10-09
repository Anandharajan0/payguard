package com.anandharajan.payguard.policy;

public record RefundExecutionResult(
        RefundDecision decision,
        String refundId,
        String paypalStatus,
        String approvalId,
        String operationId,
        String correlationId,
        RefundState state,
        String error,
        java.time.Instant occurredAt
) {}
