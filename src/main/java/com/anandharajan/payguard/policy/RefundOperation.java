package com.anandharajan.payguard.policy;

import java.time.Instant;
import java.time.Clock;
import com.anandharajan.payguard.paypal.PayPalRefundOutcome;

public final class RefundOperation {

    private final String operationId;
    private final String idempotencyKey;
    private final String requestFingerprint;
    private final RefundRequest request;
    private final String requesterAgentId;
    private final String mandateId;
    private String approvalId;
    private final RefundDecision decision;
    private final Clock clock;
    private RefundState state;
    private RefundExecutionResult result;

    public RefundOperation(
            String operationId,
            String idempotencyKey,
            String requestFingerprint,
            RefundRequest request,
            RefundDecision decision,
            Clock clock
    ) {
        this.operationId = operationId;
        this.idempotencyKey = idempotencyKey;
        this.requestFingerprint = requestFingerprint;
        this.request = request;
        this.clock = clock;
        this.requesterAgentId = request == null || request.agentContext() == null
                ? null
                : request.agentContext().agentId();
        this.mandateId = request == null || request.agentContext() == null
                || request.agentContext().merchantMandate() == null
                ? null
                : request.agentContext().merchantMandate().mandateId();
        this.approvalId = decision.state() == RefundState.PENDING_APPROVAL
                ? java.util.UUID.randomUUID().toString()
                : null;
        this.decision = decision;
        this.state = decision.state();
        this.result = result(null, null, null);
    }

    public synchronized boolean beginAutomaticExecution() {
        if (state != RefundState.APPROVED) {
            return false;
        }
        state = RefundState.IN_FLIGHT;
        result = result(null, null, null);
        return true;
    }

    public synchronized boolean approve(ApproverContext approver) {
        if (approver == null
                || !approver.isAuthorizedFor(mandateId)) {
            throw new ApprovalScopeException(
                    "Approver is not authorized for the refund mandate"
            );
        }
        if (requesterAgentId != null
                && requesterAgentId.equals(approver.approverId())) {
            throw new SelfApprovalException(
                    "The requesting agent cannot approve its own refund"
            );
        }
        if (state != RefundState.PENDING_APPROVAL) {
            return false;
        }
        state = RefundState.IN_FLIGHT;
        result = result(null, null, null);
        return true;
    }

    public synchronized void complete(PayPalRefundOutcome outcome) {
        if (outcome == null) {
            throw new IllegalArgumentException("PayPal outcome is required");
        }
        if (state != RefundState.IN_FLIGHT) {
            throw new IllegalStateException(
                    "Only an in-flight operation can be completed"
            );
        }
        state = outcome.state();
        result = result(
                outcome.refundId(),
                outcome.paypalStatus(),
                outcome.error()
        );
    }

    public synchronized RefundExecutionResult result() {
        return result;
    }

    public String operationId() {
        return operationId;
    }

    public String idempotencyKey() {
        return idempotencyKey;
    }

    public String requestFingerprint() {
        return requestFingerprint;
    }

    public RefundRequest request() {
        return request;
    }

    public String requesterAgentId() {
        return requesterAgentId;
    }

    public String mandateId() {
        return mandateId;
    }

    public String approvalId() {
        return approvalId;
    }

    public RefundDecision decision() {
        return decision;
    }

    public synchronized RefundState state() {
        return state;
    }

    synchronized void restore(
            RefundState restoredState,
            String restoredApprovalId,
            String refundId,
            String paypalStatus,
            String error
    ) {
        this.approvalId = restoredApprovalId;
        this.state = restoredState;
        this.result = result(refundId, paypalStatus, error);
    }

    private RefundExecutionResult result(
            String refundId,
            String paypalStatus,
            String error
    ) {
        return new RefundExecutionResult(
                decision,
                refundId,
                paypalStatus,
                approvalId,
                operationId,
                decision.correlationId(),
                state,
                error,
                clock.instant()
        );
    }
}
