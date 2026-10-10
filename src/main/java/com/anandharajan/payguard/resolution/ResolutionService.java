package com.anandharajan.payguard.resolution;

import com.anandharajan.payguard.policy.AgentContext;
import com.anandharajan.payguard.policy.RefundDecision;
import com.anandharajan.payguard.policy.RefundExecutionResult;
import com.anandharajan.payguard.policy.RefundRequest;
import com.anandharajan.payguard.policy.RefundService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Clock;

@Service
public final class ResolutionService {

    private final TransactionEvidenceProvider evidenceProvider;
    private final PaymentResolutionAgent agent;
    private final RefundService refundService;
    private final Clock clock;
    private final String mandateId;

    public ResolutionService(
            TransactionEvidenceProvider evidenceProvider,
            PaymentResolutionAgent agent,
            RefundService refundService,
            Clock clock,
            @Value("${payguard.security.mandate-id}") String mandateId
    ) {
        this.evidenceProvider = evidenceProvider;
        this.agent = agent;
        this.refundService = refundService;
        this.clock = clock;
        this.mandateId = mandateId;
    }

    public ResolutionCaseResponse resolve(
            ResolutionCaseRequest request,
            String actorId
    ) {
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

        ResolutionProposal proposal = agent.propose(
                request.customerIssue(), evidence
        );
        RefundDecision decision;
        RefundExecutionResult execution = null;
        if (!"PROPOSE_REFUND".equals(proposal.action())) {
            decision = new RefundDecision(
                    RefundDecision.Status.DENIED,
                    "RESOLUTION_REQUIRES_MORE_INFORMATION"
            );
        } else {
            validateProposal(proposal, evidence);
            RefundRequest refundRequest = new RefundRequest(
                    evidence.captureId(),
                    evidence.amountCents(),
                    evidence.currency(),
                    evidence.capturedAt().isAfter(clock.instant())
                            ? clock.instant()
                            : evidence.capturedAt(),
                    AgentContext.authenticatedAgent(actorId, mandateId)
            );
            execution = refundService.refund(
                    refundRequest,
                    request.idempotencyKey()
            );
            decision = execution.decision();
        }
        return new ResolutionCaseResponse(
                evidence, proposal, decision, execution
        );
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
        if (proposal.proposedAmountCents() != evidence.amountCents()) {
            throw new IllegalArgumentException(
                    "Resolution proposal cannot change the trusted amount"
            );
        }
    }
}
