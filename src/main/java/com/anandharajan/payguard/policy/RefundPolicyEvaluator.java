package com.anandharajan.payguard.policy;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

public final class RefundPolicyEvaluator {

    private final RefundPolicy policy;
    private final Clock clock;

    public RefundPolicyEvaluator(RefundPolicy policy, Clock clock) {
        this.policy = policy;
        this.clock = clock;
    }

    public RefundDecision evaluate(RefundRequest request) {
        if (request.amountCents() == null || request.amountCents() <= 0) {
            return new RefundDecision(
                    RefundDecision.Status.DENIED,
                    "REFUND_AMOUNT_REQUIRED"
            );
        }

        if (request.transactionCreatedAt() == null) {
            return new RefundDecision(
                    RefundDecision.Status.DENIED,
                    "TRANSACTION_DATE_REQUIRED"
            );
        }

        Instant cutoff = clock.instant()
                .minus(policy.maxTransactionAgeDays(), ChronoUnit.DAYS);

        if (request.transactionCreatedAt().isBefore(cutoff)) {
            return new RefundDecision(
                    RefundDecision.Status.DENIED,
                    "TRANSACTION_TOO_OLD"
            );
        }

        if (request.amountCents() <= policy.autoApproveMaxCents()) {
            return new RefundDecision(
                    RefundDecision.Status.APPROVED,
                    "WITHIN_AUTO_APPROVAL_LIMIT"
            );
        }

        if (request.amountCents() <= policy.humanApprovalMaxCents()) {
            return new RefundDecision(
                    RefundDecision.Status.PENDING_APPROVAL,
                    "HUMAN_APPROVAL_REQUIRED"
            );
        }

        return new RefundDecision(
                RefundDecision.Status.DENIED,
                "EXCEEDS_HUMAN_APPROVAL_LIMIT"
        );
    }
}
