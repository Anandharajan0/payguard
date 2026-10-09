package com.anandharajan.payguard.policy;

import com.anandharajan.payguard.paypal.PayPalRefundOutcome;

public interface RefundOperationRepository {

    RefundOperation getOrCreate(
            String idempotencyKey,
            RefundRequest request,
            RefundDecision decision
    );

    RefundOperation findByApprovalId(String approvalId);

    RefundOperation findByIdempotencyKey(String idempotencyKey);

    boolean beginAutomaticExecution(String operationId);

    boolean approve(String approvalId, ApproverContext approver);

    RefundOperation complete(String operationId, PayPalRefundOutcome outcome);

    int recoverStaleOperations(java.time.Instant now);
}
