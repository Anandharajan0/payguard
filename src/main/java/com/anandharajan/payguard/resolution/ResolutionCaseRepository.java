package com.anandharajan.payguard.resolution;

import java.util.function.Function;

public interface ResolutionCaseRepository {

    ResolutionCase findByKey(String mandateId, String idempotencyKey);

    ResolutionCaseClaim claim(
            String mandateId,
            String idempotencyKey,
            String requestFingerprint,
            String customerIssue,
            TransactionEvidence evidence
    );

    ResolutionCase processProposal(
            String caseId,
            String claimToken,
            Function<ResolutionCase, ResolutionProposalResult> processor
    );

    ResolutionCase linkOperation(
            String caseId,
            String operationId
    );
}
