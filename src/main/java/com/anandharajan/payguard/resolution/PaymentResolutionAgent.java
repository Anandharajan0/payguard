package com.anandharajan.payguard.resolution;

public interface PaymentResolutionAgent {

    ResolutionProposal propose(
            String customerIssue,
            TransactionEvidence evidence
    );
}
