package com.anandharajan.payguard.resolution;

import com.anandharajan.payguard.policy.RefundDecision;
import com.anandharajan.payguard.policy.RefundExecutionResult;

public record ResolutionCaseResponse(
        TransactionEvidence evidence,
        ResolutionProposal proposal,
        RefundDecision decision,
        RefundExecutionResult execution
) {}
