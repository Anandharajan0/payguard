package com.anandharajan.payguard.policy;

import org.springframework.stereotype.Component;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.util.HexFormat;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Component
@ConditionalOnProperty(
        name = "payguard.persistence.mode",
        havingValue = "in-memory",
        matchIfMissing = false
)
public class RefundOperationStore implements RefundOperationRepository {

    private final Map<String, RefundOperation> operationsByKey =
            new ConcurrentHashMap<>();
    private final Map<String, RefundOperation> operationsByApproval =
            new ConcurrentHashMap<>();
    private final Clock clock;

    public RefundOperationStore(Clock clock) {
        this.clock = clock;
    }

    public synchronized RefundOperation getOrCreate(
            String idempotencyKey,
            RefundRequest request,
            RefundDecision decision
    ) {
        String fingerprint = fingerprint(request);
        RefundOperation existing = operationsByKey.get(idempotencyKey);
        if (existing != null) {
            if (!existing.requestFingerprint().equals(fingerprint)) {
                throw new IdempotencyKeyException(
                        "Idempotency-Key was already used for a different refund request"
                );
            }
            return existing;
        }

        RefundOperation operation = new RefundOperation(
                java.util.UUID.randomUUID().toString(),
                idempotencyKey,
                fingerprint,
                request,
                decision,
                clock
        );
        operationsByKey.put(idempotencyKey, operation);
        if (operation.approvalId() != null) {
            operationsByApproval.put(operation.approvalId(), operation);
        }
        return operation;
    }

    public RefundOperation findByApprovalId(String approvalId) {
        return operationsByApproval.get(approvalId);
    }

    public RefundOperation findByIdempotencyKey(String idempotencyKey) {
        return operationsByKey.get(idempotencyKey);
    }

    @Override
    public RefundOperation findByOperationId(String operationId) {
        return findByOperationIdInternal(operationId);
    }

    @Override
    public synchronized boolean beginAutomaticExecution(String operationId) {
        RefundOperation operation = findByOperationIdInternal(operationId);
        return operation != null && operation.beginAutomaticExecution();
    }

    @Override
    public synchronized boolean approve(
            String approvalId,
            ApproverContext approver
    ) {
        RefundOperation operation = findByApprovalId(approvalId);
        return operation != null && operation.approve(approver);
    }

    @Override
    public synchronized RefundOperation complete(
            String operationId,
            com.anandharajan.payguard.paypal.PayPalRefundOutcome outcome
    ) {
        RefundOperation operation = findByOperationIdInternal(operationId);
        if (operation == null) {
            return null;
        }
        operation.complete(outcome);
        return operation;
    }

    @Override
    public int recoverStaleOperations(java.time.Instant now) {
        return 0;
    }

    private RefundOperation findByOperationIdInternal(String operationId) {
        return operationsByKey.values().stream()
                .filter(operation -> operation.operationId().equals(operationId))
                .findFirst()
                .orElse(null);
    }

    public static String fingerprint(RefundRequest request) {
        if (request == null) {
            return "NULL_REQUEST";
        }
        String value = String.join("|",
                String.valueOf(request.captureId()),
                String.valueOf(request.amountCents()),
                String.valueOf(request.currency()),
                String.valueOf(request.transactionCreatedAt()),
                request.agentContext() == null
                        ? ""
                        : String.valueOf(request.agentContext().agentId()),
                request.agentContext() == null
                        || request.agentContext().merchantMandate() == null
                        ? ""
                        : String.valueOf(
                                request.agentContext().merchantMandate().mandateId()
                        ));
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256")
                            .digest(value.getBytes(StandardCharsets.UTF_8))
            );
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }
}
