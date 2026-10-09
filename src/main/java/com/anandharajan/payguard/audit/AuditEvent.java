package com.anandharajan.payguard.audit;

import com.anandharajan.payguard.policy.RefundState;

import java.time.Instant;

public record AuditEvent(
        String operationId,
        String correlationId,
        RefundState state,
        String explanation,
        String actorId,
        String actorRole,
        String mandateId,
        String approvalId,
        String captureId,
        Long amountCents,
        String currency,
        String paypalRefundId,
        String paypalStatus,
        String error,
        Instant occurredAt
) {}
