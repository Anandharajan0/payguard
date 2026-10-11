package com.anandharajan.payguard.resolution;

import java.time.Instant;

public record ResolutionCase(
        String caseId,
        String mandateId,
        String idempotencyKey,
        String requestFingerprint,
        String customerIssue,
        TransactionEvidence evidence,
        ResolutionProposal proposal,
        ResolutionCaseStatus status,
        String operationId,
        String processingClaimToken,
        Instant processingLeaseExpiresAt,
        String failureCode
) {}
