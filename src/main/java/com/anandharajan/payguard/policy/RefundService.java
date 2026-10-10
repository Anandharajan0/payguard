package com.anandharajan.payguard.policy;

import com.anandharajan.payguard.audit.AuditEvent;
import com.anandharajan.payguard.audit.AuditSink;
import com.anandharajan.payguard.paypal.PayPalRefundGateway;
import com.anandharajan.payguard.paypal.PayPalRefundOutcome;
import com.anandharajan.payguard.paypal.PayPalRefundOutcomeMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import tools.jackson.databind.JsonNode;

import java.time.Clock;
import java.util.function.Supplier;

@Service
public class RefundService {

    private final RefundPolicyEvaluator evaluator;
    private final PayPalRefundGateway payPalClient;
    private final RefundOperationRepository operationStore;
    private final AuditSink auditSink;
    private final Clock clock;
    private final PayPalRefundOutcomeMapper outcomeMapper;
    private final TransactionTemplate transactions;

    @Autowired
    public RefundService(
            PayPalRefundGateway payPalClient,
            RefundOperationRepository operationStore,
            BudgetDecision budgetDecision,
            AuditSink auditSink,
            Clock clock,
            @Nullable PlatformTransactionManager transactionManager
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
        this.transactions = transactionManager == null
                ? null
                : new TransactionTemplate(transactionManager);
    }

    public RefundService(
            PayPalRefundGateway payPalClient,
            RefundOperationRepository operationStore,
            BudgetDecision budgetDecision,
            AuditSink auditSink,
            Clock clock
    ) {
        this(
                payPalClient,
                operationStore,
                budgetDecision,
                auditSink,
                clock,
                null
        );
    }

    public RefundService(
            PayPalRefundGateway payPalClient,
            RefundOperationRepository operationStore,
            AuditSink auditSink,
            Clock clock
    ) {
        this(
                payPalClient,
                operationStore,
                new NoOpBudgetDecision(),
                auditSink,
                clock,
                null
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

    public RefundOperation findByOperationId(String operationId) {
        return operationStore.findByOperationId(operationId);
    }

    public RefundExecutionResult refund(
            RefundRequest request,
            String idempotencyKey
    ) {
        validateIdempotencyKey(idempotencyKey);
        RefundDecision decision = evaluator.evaluate(request);
        RefundOperation operation = transactional(() -> {
            RefundOperation created = operationStore.getOrCreate(
                    idempotencyKey,
                    request,
                    decision
            );
            auditDecision(
                    created,
                    request,
                    decision,
                    "request_accepted",
                    request.agentContext() == null
                            ? null
                            : request.agentContext().agentId()
            );
            return created;
        });

        if (operation.state() == RefundState.DENIED) {
            return operation.result();
        }
        if (operation.state() != RefundState.APPROVED) {
            return operation.result();
        }
        boolean begun = transactional(() -> {
            boolean started = operationStore.beginAutomaticExecution(
                    operation.operationId()
            );
            if (started) {
                auditState(operation, RefundState.IN_FLIGHT,
                        request.agentContext() == null
                                ? null
                                : request.agentContext().agentId(),
                        "PayPal execution started", "AGENT", null, null);
            }
            return started;
        });
        if (!begun) {
            return operation.result();
        }
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
        boolean approved = transactional(() -> {
            boolean changed = operationStore.approve(approvalId, approver);
            if (changed) {
                auditState(operation, RefundState.IN_FLIGHT,
                        approver.approverId(),
                        "Human approval granted; PayPal execution started",
                        "APPROVER", null, null);
            }
            return changed;
        });
        if (!approved) {
            return operation.result();
        }
        return execute(operation, approver.approverId(), "APPROVER");
    }

    private RefundExecutionResult execute(
            RefundOperation operation,
            String actorId,
            String actorRole
    ) {
        RefundRequest request = operation.request();
        PayPalRefundOutcome outcome = null;
        JsonNode response = null;
        try {
            response = payPalClient.refund(
                    request.captureId(),
                    request.amountCents(),
                    request.currency(),
                    operation.operationId()
            );
        } catch (RestClientResponseException exception) {
            outcome = outcomeMapper.mapHttpFailure(
                    exception.getStatusCode().value()
            );
        } catch (RestClientException exception) {
            outcome = outcomeMapper.mapAmbiguousTransportFailure();
        } catch (RuntimeException exception) {
            outcome = outcomeMapper.mapAmbiguousTransportFailure();
        }
        if (outcome == null) {
            outcome = outcomeMapper.map(response);
        }
        return completeAndAudit(
                operation,
                outcome,
                actorId,
                actorRole,
                outcome.error() == null
                        ? "PayPal refund completed"
                        : outcome.error()
        ).result();
    }

    private RefundOperation completeAndAudit(
            RefundOperation operation,
            PayPalRefundOutcome outcome,
            String actorId,
            String actorRole,
            String explanation
    ) {
        return transactional(() -> {
            RefundOperation completed = operationStore.complete(
                    operation.operationId(), outcome
            );
            auditState(completed, outcome.state(), actorId, explanation,
                    actorRole, outcome.refundId(), outcome.paypalStatus());
            return completed;
        });
    }

    private <T> T transactional(Supplier<T> action) {
        if (transactions == null) {
            return action.get();
        }
        return transactions.execute(status -> action.get());
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
