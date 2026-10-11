package com.anandharajan.payguard.resolution;

public record ResolutionProposalResult(
        ResolutionProposal proposal,
        ResolutionCaseStatus status,
        String failureCode
) {}
