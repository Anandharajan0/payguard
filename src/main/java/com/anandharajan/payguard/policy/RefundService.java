package com.anandharajan.payguard.policy;

import com.anandharajan.payguard.audit.AuditEvent;
import com.anandharajan.payguard.audit.AuditSink;
import com.anandharajan.payguard.paypal.PayPalRefundGateway;
import com.anandharajan.payguard.paypal.PayPalRefundOutcome;
import com.anandharajan.payguard.paypal.PayPalRefundOutcomeMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClientResponseException;
import tools.jackson.databind.JsonNode;

import java.time.Clock;

@Service
public class RefundService {

    private final RefundPolicyEvaluator evaluator;
    private final PayPalRefundGateway payPalClient;
    private final RefundOperationStore operationStore;
    private final AuditSink auditSink;
    private final Clock clock;
    private final PayPalRefundOutcomeMapper outcomeMapper;

    @Autowired
    public RefundService(
            PayPalRefundGateway payPalClient,
            RefundOperationStore operationStore,
            BudgetDecision budgetDecision,
            AuditSink auditSink,
            Clock clock
    ) {
        RefundPolicy policy = new RefundPolicy(
                5_000,
                50_000,
                200_000,
                30
        );
        this.evaluator = new RefundPolicyEvaluator(
                policy,
                clock,
                budgetDecision
        );
        this.payPalClient = payPalClient;
        this.operationStore = operationStore;
        this.auditSink = auditSink;
        this.clock = clock;
        this.outcomeMapper = new PayPalRefundOutcomeMapper();
    }

    public RefundService(
            PayPalRefundGateway payPalClient,
            RefundOperationStore operationStore,
            AuditSink auditSink,
            Clock clock
    ) {
        this(
                payPalClient,
                operationStore,
                new NoOpBudgetDecision(),
                auditSink,
                clock
        );
    }

    public RefundDecision evaluate(RefundRequest request) {
        return evaluate(request, null);
    }

    public RefundDecision evaluate(
            RefundRequest request,
            String actorId
    ) {
        RefundDecision decision = evaluator.evaluate(request);
        auditDecision(null, request, decision, "evaluate", actorId);
        return decision;
    }

    public RefundExecutionResult refund(
            RefundRequest request,
            String idempotencyKey
    ) {
        validateIdempotencyKey(idempotencyKey);
        RefundDecision decision = evaluator.evaluate(request);
        RefundOperation operation = operationStore.getOrCreate(
                idempotencyKey,
                request,
                decision
        );
        auditDecision(
                operation,
                request,
                decision,
                "request_accepted",
                request.agentContext() == null
                        ? null
                        : request.agentContext().agentId()
        );

        if (operation.state() == RefundState.DENIED) {
            return operation.result();
        }
        if (operation.state() != RefundState.APPROVED
                || !operation.beginAutomaticExecution()) {
            return operation.result();
        }

        auditState(operation, RefundState.IN_FLIGHT,
                request.agentContext() == null
                        ? null
                        : request.agentContext().agentId(),
                "PayPal execution started", null, null, null);
        return execute(
                operation,
                request.agentContext().agentId(),
                "AGENT"
        );
    }

    public RefundExecutionResult approve(
            String approvalId,
            ApproverContext approver
    ) {
        RefundOperation operation = operationStore.findByApprovalId(approvalId);
        if (operation == null) {
            RefundDecision decision = new RefundDecision(
                    RefundDecision.Status.DENIED,
                    "APPROVAL_NOT_FOUND"
            );
            return new RefundExecutionResult(
                    decision,
                    null,
                    null,
                    approvalId,
                    null,
                    decision.correlationId(),
                    RefundState.DENIED,
                    "APPROVAL_NOT_FOUND",
                    clock.instant()
            );
        }
        if (!operation.approve(approver)) {
            return operation.result();
        }

        auditState(operation, RefundState.IN_FLIGHT, approver.approverId(),
                "Human approval granted; PayPal execution started",
                "APPROVER", null, null);
        return execute(operation, approver.approverId(), "APPROVER");
    }

    private RefundExecutionResult execute(
            RefundOperation operation,
            String actorId,
            String actorRole
    ) {
        RefundRequest request = operation.request();
        try {
            JsonNode response = payPalClient.refund(
                    request.captureId(),
                    request.amountCents(),
                    request.currency(),
                    operation.operationId()
            );
            PayPalRefundOutcome outcome = outcomeMapper.map(response);
            operation.complete(outcome);
            auditState(operation, outcome.state(), actorId,
                    outcome.error() == null
                            ? "PayPal refund completed"
                            : outcome.error(),
                    actorRole, outcome.refundId(), outcome.paypalStatus());
            return operation.result();
        } catch (RestClientResponseException exception) {
            PayPalRefundOutcome outcome = outcomeMapper.mapHttpFailure(
                    exception.getStatusCode().value()
            );
            operation.complete(outcome);
            auditState(operation, outcome.state(), actorId, outcome.error(),
                    actorRole, outcome.refundId(), outcome.paypalStatus());
            return operation.result();
        } catch (RuntimeException exception) {
            PayPalRefundOutcome outcome =
                    outcomeMapper.mapAmbiguousTransportFailure();
            operation.complete(outcome);
            auditState(operation, outcome.state(), actorId,
                    "PayPal outcome is ambiguous and requires reconciliation",
                    actorRole, outcome.refundId(), outcome.paypalStatus());
            return operation.result();
        }
    }

    private void validateIdempotencyKey(String idempotencyKey) {
        if (idempotencyKey == null
                || !idempotencyKey.matches("[A-Za-z0-9._:-]{8,128}")) {
            throw new InvalidIdempotencyKeyException(
                    "Idempotency-Key must be 8-128 safe ASCII characters"
            );
        }
    }

    private void auditDecision(
            RefundOperation operation,
            RefundRequest request,
            RefundDecision decision,
            String explanation,
            String actorId
    ) {
        auditSink.record(new AuditEvent(
                operation == null ? null : operation.operationId(),
                decision.correlationId(),
                decision.state(),
                explanation + ": " + decision.reason(),
                actorId,
                actorId == null ? null : "AGENT",
                mandateId(request),
                operation == null ? null : operation.approvalId(),
                request == null ? null : request.captureId(),
                request == null ? null : request.amountCents(),
                request == null ? null : request.currency(),
                null,
                null,
                null,
                clock.instant()
        ));
    }

    private String mandateId(RefundRequest request) {
        if (request == null
                || request.agentContext() == null
                || request.agentContext().merchantMandate() == null) {
            return null;
        }
        return request.agentContext().merchantMandate().mandateId();
    }

    private void auditState(
            RefundOperation operation,
            RefundState state,
            String actorId,
            String explanation,
            String actorRole,
            String refundId,
            String paypalStatus
    ) {
        RefundRequest request = operation.request();
        auditSink.record(new AuditEvent(
                operation.operationId(),
                operation.decision().correlationId(),
                state,
                explanation,
                actorId,
                actorRole == null ? "AGENT" : actorRole,
                operation.mandateId(),
                operation.approvalId(),
                request.captureId(),
                request.amountCents(),
                request.currency(),
                refundId,
                paypalStatus,
                operation.result().error(),
                clock.instant()
        ));
    }
}
