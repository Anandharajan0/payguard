package com.anandharajan.payguard.resolution;

import java.util.List;

public record ResolutionProposal(
        String action,
        long proposedAmountCents,
        String captureId,
        double confidence,
        String explanation,
        List<String> evidence,
        List<String> missingInformation,
        String agentVersion
) {
    public ResolutionProposal {
        evidence = evidence == null ? List.of() : List.copyOf(evidence);
        missingInformation = missingInformation == null
                ? List.of()
                : List.copyOf(missingInformation);
    }
}
