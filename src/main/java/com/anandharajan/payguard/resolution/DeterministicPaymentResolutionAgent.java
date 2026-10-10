package com.anandharajan.payguard.resolution;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;

@Component
public final class DeterministicPaymentResolutionAgent
        implements PaymentResolutionAgent {

    @Override
    public ResolutionProposal propose(
            String customerIssue,
            TransactionEvidence evidence
    ) {
        String issue = customerIssue == null
                ? ""
                : customerIssue.toLowerCase(Locale.ROOT);
        boolean refundSignal = issue.contains("refund")
                || issue.contains("duplicate")
                || issue.contains("charged")
                || issue.contains("not receive")
                || issue.contains("not received");
        if (!refundSignal) {
            return new ResolutionProposal(
                    "REQUEST_MORE_INFORMATION",
                    0,
                    evidence.captureId(),
                    0.42,
                    "The issue does not contain enough evidence for a refund proposal",
                    List.of("trusted capture exists", "issue classification is inconclusive"),
                    List.of("customer issue details"),
                    "fixture-resolution-agent-v1"
            );
        }
        return new ResolutionProposal(
                "PROPOSE_REFUND",
                evidence.amountCents(),
                evidence.captureId(),
                0.96,
                "The issue indicates a refund-related payment problem; propose the trusted captured amount",
                List.of(
                        "capture ID came from trusted transaction evidence",
                        "amount came from trusted transaction evidence",
                        "currency came from trusted transaction evidence"
                ),
                List.of(),
                "fixture-resolution-agent-v1"
        );
    }
}
