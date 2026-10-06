package com.anandharajan.payguard.policy;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class RefundPolicyEvaluatorTest {

    private RefundPolicyEvaluator evaluator;

    @BeforeEach
    void setUp() {
        RefundPolicy policy = new RefundPolicy(
                5_000,
                50_000,
                200_000,
                30
        );

        Clock clock = Clock.fixed(
                Instant.parse("2026-10-06T00:00:00Z"),
                ZoneOffset.UTC
        );

        evaluator = new RefundPolicyEvaluator(policy, clock);
    }

    @Test
    void approvesRefundWithinAutoApprovalLimit() {
        RefundRequest request = new RefundRequest(
                "CAPTURE-1",
                5_000L,
                "USD",
                Instant.parse("2026-10-01T00:00:00Z")
        );

        RefundDecision decision = evaluator.evaluate(request);

        assertEquals(RefundDecision.Status.APPROVED, decision.status());
    }

    @Test
    void requiresApprovalAboveAutoApprovalLimit() {
        RefundRequest request = new RefundRequest(
                "CAPTURE-2",
                30_000L,
                "USD",
                Instant.parse("2026-10-01T00:00:00Z")
        );

        RefundDecision decision = evaluator.evaluate(request);

        assertEquals(
                RefundDecision.Status.PENDING_APPROVAL,
                decision.status()
        );
    }

    @Test
    void deniesRefundAboveHumanApprovalLimit() {
        RefundRequest request = new RefundRequest(
                "CAPTURE-3",
                60_000L,
                "USD",
                Instant.parse("2026-10-01T00:00:00Z")
        );

        RefundDecision decision = evaluator.evaluate(request);

        assertEquals(RefundDecision.Status.DENIED, decision.status());
    }

    @Test
    void deniesRefundWhenAmountIsMissing() {
        RefundRequest request = new RefundRequest(
                "CAPTURE-4",
                null,
                "USD",
                Instant.parse("2026-10-01T00:00:00Z")
        );

        RefundDecision decision = evaluator.evaluate(request);

        assertEquals(RefundDecision.Status.DENIED, decision.status());
        assertEquals("REFUND_AMOUNT_REQUIRED", decision.reason());
    }

    @Test
    void deniesRefundWhenTransactionIsTooOld() {
        RefundRequest request = new RefundRequest(
                "CAPTURE-5",
                5_000L,
                "USD",
                Instant.parse("2026-09-01T00:00:00Z")
        );

        RefundDecision decision = evaluator.evaluate(request);

        assertEquals(RefundDecision.Status.DENIED, decision.status());
    }
}
