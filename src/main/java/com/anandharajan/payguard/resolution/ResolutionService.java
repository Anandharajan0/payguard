package com.anandharajan.payguard.resolution;

import com.anandharajan.payguard.policy.AgentContext;
import com.anandharajan.payguard.policy.IdempotencyKeyException;
import com.anandharajan.payguard.policy.RefundDecision;
import com.anandharajan.payguard.policy.RefundExecutionResult;
import com.anandharajan.payguard.policy.RefundOperation;
import com.anandharajan.payguard.policy.RefundRequest;
import com.anandharajan.payguard.policy.RefundService;
import com.anandharajan.payguard.policy.RefundState;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

@Service
public final class ResolutionService {

    private static final String REFUND_IDEMPOTENCY_NAMESPACE =
            "payguard-resolution:";

    private final TransactionEvidenceProvider evidenceProvider;
    private final PaymentResolutionAgent agent;
    private final RefundService refundService;
    private final ResolutionCaseRepository caseRepository;
    private final java.time.Clock clock;
    private final String mandateId;

    public ResolutionService(
            TransactionEvidenceProvider evidenceProvider,
            PaymentResolutionAgent agent,
            RefundService refundService,
            ResolutionCaseRepository caseRepository,
            java.time.Clock clock,
            @Value("${payguard.security.mandate-id}") String mandateId
    ) {
        this.evidenceProvider = evidenceProvider;
        this.agent = agent;
        this.refundService = refundService;
        this.caseRepository = caseRepository;
        this.clock = clock;
        this.mandateId = mandateId;
    }

    public ResolutionCaseResponse resolve(
            ResolutionCaseRequest request,
            String actorId
    ) {
        validateRequest(request);
        String requestFingerprint = fingerprint(
                request.customerIssue(), request.captureId()
        );

        ResolutionCase resolutionCase = caseRepository.findByKey(
                mandateId, request.idempotencyKey()
        );
        String claimToken = null;
        if (resolutionCase != null) {
            ensureSameRequest(resolutionCase, requestFingerprint);
            if (resolutionCase.status() == ResolutionCaseStatus.PROCESSING
                    && resolutionCase.proposal() == null) {
                ResolutionCaseClaim recoveryClaim = caseRepository.claim(
                        mandateId,
                        request.idempotencyKey(),
                        requestFingerprint,
                        resolutionCase.customerIssue(),
                        resolutionCase.evidence()
                );
                resolutionCase = recoveryClaim.resolutionCase();
                claimToken = recoveryClaim.claimToken();
            }
            return continueCase(resolutionCase, claimToken, actorId);
        }

        TransactionEvidence evidence = evidenceProvider.find(request.captureId());
        if (evidence == null) {
            throw new IllegalArgumentException(
                    "No trusted transaction evidence exists for capture"
            );
        }
        if (!mandateId.equals(evidence.mandateId())) {
            throw new IllegalArgumentException(
                    "Transaction evidence is outside the configured mandate"
            );
        }

        ResolutionCaseClaim claim = caseRepository.claim(
                mandateId,
                request.idempotencyKey(),
                requestFingerprint,
                request.customerIssue(),
                evidence
        );
        resolutionCase = claim.resolutionCase();
        ensureSameRequest(resolutionCase, requestFingerprint);
        return continueCase(
                resolutionCase, claim.claimToken(), actorId
        );
    }

    private ResolutionCaseResponse continueCase(
            ResolutionCase resolutionCase,
            String claimToken,
            String actorId
    ) {
        if (resolutionCase.proposal() == null) {
            if (claimToken == null) {
                return toResponse(resolutionCase);
            }
            resolutionCase = caseRepository.processProposal(
                    resolutionCase.caseId(), claimToken,
                    this::generateProposal
            );
            if (resolutionCase.status() == ResolutionCaseStatus.PROCESSING) {
                return toResponse(resolutionCase);
            }
        }

        if (resolutionCase.proposal() == null
                || !"PROPOSE_REFUND".equals(
                        resolutionCase.proposal().action())
                || resolutionCase.status() == ResolutionCaseStatus.INFORMATION_REQUIRED
                || resolutionCase.status() == ResolutionCaseStatus.PROPOSAL_REJECTED
                || resolutionCase.status() == ResolutionCaseStatus.MODEL_FAILED) {
            return toResponse(resolutionCase);
        }
        return resumeRefund(resolutionCase, actorId);
    }

    private ResolutionProposalResult generateProposal(ResolutionCase current) {
        ResolutionProposal proposal;
        try {
            proposal = agent.propose(
                    current.customerIssue(), current.evidence()
            );
            if (proposal == null) {
                throw new IllegalStateException(
                        "Resolution model returned no proposal"
                );
            }
        } catch (IllegalArgumentException exception) {
            return new ResolutionProposalResult(
                    null,
                    ResolutionCaseStatus.PROPOSAL_REJECTED,
                    "MODEL_OUTPUT_REJECTED"
            );
        } catch (RuntimeException exception) {
            return new ResolutionProposalResult(
                    null,
                    ResolutionCaseStatus.MODEL_FAILED,
                    "MODEL_FAILED"
            );
        }

        if (!"PROPOSE_REFUND".equals(proposal.action())) {
            return new ResolutionProposalResult(
                    proposal,
                    ResolutionCaseStatus.INFORMATION_REQUIRED,
                    null
            );
        }
        try {
            validateProposal(proposal, current.evidence());
        } catch (RuntimeException exception) {
            return new ResolutionProposalResult(
                    proposal,
                    ResolutionCaseStatus.PROPOSAL_REJECTED,
                    "MODEL_PROPOSAL_UNSAFE"
            );
        }
        TransactionEvidence evidence = current.evidence();
        if (!"COMPLETED".equals(evidence.status())
                || evidence.remainingRefundableCents() <= 0) {
            return new ResolutionProposalResult(
                    proposal,
                    ResolutionCaseStatus.PROPOSAL_REJECTED,
                    "CAPTURE_NOT_REFUNDABLE"
            );
        }
        return new ResolutionProposalResult(
                proposal, ResolutionCaseStatus.PROPOSAL_READY, null
        );
    }

    private ResolutionCaseResponse resumeRefund(
            ResolutionCase resolutionCase,
            String actorId
    ) {
        String operationKey = operationKey(resolutionCase.caseId());
        RefundOperation operation = resolutionCase.operationId() == null
                ? refundService.findByIdempotencyKey(mandateId, operationKey)
                : refundService.findByOperationId(resolutionCase.operationId());
        RefundExecutionResult execution;
        if (operation == null) {
            TransactionEvidence evidence = resolutionCase.evidence();
            RefundRequest refundRequest = new RefundRequest(
                    evidence.captureId(),
                    resolutionCase.proposal().proposedAmountCents(),
                    evidence.currency(),
                    evidence.capturedAt().isAfter(clock.instant())
                            ? clock.instant()
                            : evidence.capturedAt(),
                    AgentContext.authenticatedAgent(actorId, mandateId)
            );
            try {
                execution = refundService.refund(refundRequest, operationKey);
            } catch (IdempotencyKeyException concurrentCreate) {
                operation = refundService.findByIdempotencyKey(
                        mandateId, operationKey
                );
                if (operation == null) {
                    throw concurrentCreate;
                }
                validateLinkedOperation(operation, resolutionCase);
                execution = resumeExistingOperation(operation);
            }
        } else {
            if (!mandateId.equals(operation.mandateId())) {
                throw new IllegalStateException(
                        "Resolution refund operation belongs to a different mandate"
                );
            }
            validateLinkedOperation(operation, resolutionCase);
            execution = resumeExistingOperation(operation);
        }

        resolutionCase = caseRepository.linkOperation(
                resolutionCase.caseId(), execution.operationId()
        );
        return toResponse(resolutionCase);
    }

    private RefundExecutionResult resumeExistingOperation(
            RefundOperation operation
    ) {
        if (operation.state() == RefundState.APPROVED) {
            return refundService.resumeAutomaticExecution(
                    operation.operationId()
            );
        }
        return operation.result();
    }

    private void validateLinkedOperation(
            RefundOperation operation,
            ResolutionCase resolutionCase
    ) {
        TransactionEvidence evidence = resolutionCase.evidence();
        if (!operationKey(resolutionCase.caseId())
                        .equals(operation.idempotencyKey())
                || !evidence.captureId().equals(
                        operation.request().captureId())
                || resolutionCase.proposal() == null
                || resolutionCase.proposal().proposedAmountCents()
                        != operation.request().amountCents()
                || !evidence.currency().equals(
                        operation.request().currency())
                || !mandateId.equals(operation.mandateId())) {
            throw new IllegalStateException(
                    "Resolution case refund operation does not match its durable proposal"
            );
        }
    }

    private ResolutionCaseResponse toResponse(ResolutionCase resolutionCase) {
        RefundExecutionResult execution = null;
        ResolutionCaseStatus status = resolutionCase.status();
        RefundDecision decision;
        if (resolutionCase.operationId() != null) {
            RefundOperation operation = refundService.findByOperationId(
                    resolutionCase.operationId()
            );
            if (operation != null) {
                execution = operation.result();
                status = resolutionStatus(operation.state());
            }
        }
        if (execution == null) {
            String reason = resolutionCase.failureCode() != null
                    ? resolutionCase.failureCode()
                    : switch (status) {
                        case PROCESSING -> "RESOLUTION_IN_PROGRESS";
                        case INFORMATION_REQUIRED ->
                                "RESOLUTION_REQUIRES_MORE_INFORMATION";
                        case PROPOSAL_REJECTED -> "MODEL_PROPOSAL_UNSAFE";
                        case MODEL_FAILED -> "MODEL_FAILED";
                        default -> "RESOLUTION_NOT_EXECUTED";
                    };
            decision = new RefundDecision(
                    RefundDecision.Status.DENIED,
                    reason
            );
        } else {
            decision = execution.decision();
        }
        return new ResolutionCaseResponse(
                resolutionCase.caseId(),
                resolutionCase.evidence(),
                resolutionCase.proposal(),
                decision,
                execution,
                status.name()
        );
    }

    private void validateRequest(ResolutionCaseRequest request) {
        if (request == null
                || request.captureId() == null
                || request.captureId().isBlank()
                || request.idempotencyKey() == null
                || !request.idempotencyKey()
                        .matches("[A-Za-z0-9._:-]{8,128}")
                || request.customerIssue() == null
                || request.customerIssue().isBlank()) {
            throw new IllegalArgumentException(
                    "Resolution request requires issue, capture ID, and an 8-128 character idempotency key"
            );
        }
    }

    private static void ensureSameRequest(
            ResolutionCase resolutionCase,
            String requestFingerprint
    ) {
        if (!resolutionCase.requestFingerprint().equals(requestFingerprint)) {
            throw new IllegalArgumentException(
                    "Resolution idempotency key was reused for a different request"
            );
        }
    }

    private void validateProposal(
            ResolutionProposal proposal,
            TransactionEvidence evidence
    ) {
        if (!evidence.captureId().equals(proposal.captureId())) {
            throw new IllegalArgumentException(
                    "Resolution proposal cannot change the trusted capture ID"
            );
        }
        if (proposal.proposedAmountCents() <= 0) {
            throw new IllegalArgumentException(
                    "Resolution proposal amount must be positive"
            );
        }
        if (proposal.proposedAmountCents()
                != evidence.remainingRefundableCents()) {
            throw new IllegalArgumentException(
                    "Resolution proposals must match the trusted full remaining balance"
            );
        }
    }

    private static ResolutionCaseStatus resolutionStatus(RefundState state) {
        return switch (state) {
            case PENDING_APPROVAL -> ResolutionCaseStatus.PENDING_APPROVAL;
            case APPROVED -> ResolutionCaseStatus.APPROVED;
            case IN_FLIGHT -> ResolutionCaseStatus.IN_FLIGHT;
            case EXECUTED -> ResolutionCaseStatus.COMPLETED;
            case FAILED -> ResolutionCaseStatus.FAILED;
            case RECONCILIATION_REQUIRED ->
                    ResolutionCaseStatus.RECONCILIATION_REQUIRED;
            case DENIED -> ResolutionCaseStatus.DENIED;
        };
    }

    private static String operationKey(String caseId) {
        return REFUND_IDEMPOTENCY_NAMESPACE + caseId;
    }

    private static String fingerprint(String customerIssue, String captureId) {
        String value = String.valueOf(captureId) + "\u0000"
                + String.valueOf(customerIssue);
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256")
                            .digest(value.getBytes(StandardCharsets.UTF_8))
            );
        } catch (java.security.NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }
}
