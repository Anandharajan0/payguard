package com.anandharajan.payguard.resolution;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.UUID;
import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;

@Component
@ConditionalOnProperty(
        name = "payguard.persistence.mode",
        havingValue = "in-memory"
)
public final class InMemoryResolutionCaseRepository
        implements ResolutionCaseRepository {

    private final Map<String, ResolutionCase> cases = new ConcurrentHashMap<>();

    @Override
    public synchronized ResolutionCase findByKey(
            String mandateId,
            String idempotencyKey
    ) {
        return cases.get(mandateId + "|" + idempotencyKey);
    }

    @Override
    public synchronized ResolutionCaseClaim claim(
            String mandateId,
            String idempotencyKey,
            String requestFingerprint,
            String customerIssue,
            TransactionEvidence evidence
    ) {
        String key = mandateId + "|" + idempotencyKey;
        String claimToken = UUID.randomUUID().toString();
        ResolutionCase existing = cases.get(key);
        if (existing != null) {
            if (!existing.requestFingerprint().equals(requestFingerprint)) {
                throw new IllegalArgumentException(
                        "Resolution idempotency key was reused for a different request"
                );
            }
            if (existing.status() == ResolutionCaseStatus.PROCESSING
                    && existing.proposal() == null
                    && (existing.processingLeaseExpiresAt() == null
                            || existing.processingLeaseExpiresAt()
                                    .isBefore(Instant.now()))) {
                ResolutionCase reclaimed = copyWithClaim(
                        existing, claimToken,
                        Instant.now().plusSeconds(30)
                );
                cases.put(key, reclaimed);
                return new ResolutionCaseClaim(
                        reclaimed, true, claimToken
                );
            }
            return new ResolutionCaseClaim(existing, false, null);
        }
        ResolutionCase created = new ResolutionCase(
                UUID.randomUUID().toString(),
                mandateId,
                idempotencyKey,
                requestFingerprint,
                customerIssue,
                evidence,
                null,
                ResolutionCaseStatus.PROCESSING,
                null,
                claimToken,
                Instant.now().plusSeconds(30),
                null
        );
        cases.put(key, created);
        return new ResolutionCaseClaim(created, true, claimToken);
    }

    @Override
    public synchronized ResolutionCase processProposal(
            String caseId,
            String claimToken,
            java.util.function.Function<ResolutionCase, ResolutionProposalResult>
                    processor
    ) {
        ResolutionCase current = findByCaseId(caseId);
        if (current.status() != ResolutionCaseStatus.PROCESSING
                || !java.util.Objects.equals(
                        current.processingClaimToken(), claimToken)
                || current.proposal() != null) {
            return current;
        }
        ResolutionProposalResult result = processor.apply(current);
        return replace(caseId, ignored -> new ResolutionCase(
                current.caseId(), current.mandateId(), current.idempotencyKey(),
                current.requestFingerprint(), current.customerIssue(),
                current.evidence(), result.proposal(), result.status(),
                current.operationId(), null, null, result.failureCode()
        ));
    }

    @Override
    public ResolutionCase linkOperation(
            String caseId,
            String operationId
    ) {
        if (operationId == null) {
            throw new IllegalArgumentException(
                    "A refund operation ID is required"
            );
        }
        return replace(caseId, current -> {
            if (current.operationId() != null
                    && !current.operationId().equals(operationId)) {
                throw new IllegalStateException(
                        "Resolution case is linked to a different refund operation"
                );
            }
            return new ResolutionCase(
                    current.caseId(), current.mandateId(),
                    current.idempotencyKey(), current.requestFingerprint(),
                    current.customerIssue(), current.evidence(),
                    current.proposal(), ResolutionCaseStatus.REFUND_SUBMITTED,
                    operationId, null, null, current.failureCode()
            );
        });
    }

    private ResolutionCase replace(
            String caseId,
            java.util.function.UnaryOperator<ResolutionCase> update
    ) {
        for (Map.Entry<String, ResolutionCase> entry : cases.entrySet()) {
            if (entry.getValue().caseId().equals(caseId)) {
                ResolutionCase replacement = update.apply(entry.getValue());
                cases.put(entry.getKey(), replacement);
                return replacement;
            }
        }
        throw new IllegalArgumentException("Resolution case was not found");
    }

    private ResolutionCase findByCaseId(String caseId) {
        return cases.values().stream()
                .filter(candidate -> candidate.caseId().equals(caseId))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        "Resolution case was not found"
                ));
    }

    private static ResolutionCase copyWithClaim(
            ResolutionCase current,
            String claimToken,
            Instant leaseExpiresAt
    ) {
        return new ResolutionCase(
                current.caseId(), current.mandateId(),
                current.idempotencyKey(), current.requestFingerprint(),
                current.customerIssue(), current.evidence(), current.proposal(),
                current.status(), current.operationId(), claimToken,
                leaseExpiresAt, current.failureCode()
        );
    }
}
