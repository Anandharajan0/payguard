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

        evaluator = new RefundPolicyEvaluator(
                policy,
                clock,
                new DemoBudgetDecision()
        );
    }

    @Test
    void approvesRefundWithinAutoApprovalLimit() {
        RefundRequest request = new RefundRequest(
                "CAPTURE-1",
                5_000L,
                "USD",
                Instant.parse("2026-10-01T00:00:00Z"),
                AgentContext.authenticatedAgent("test-agent", "test-mandate")
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
                Instant.parse("2026-10-01T00:00:00Z"),
                AgentContext.authenticatedAgent("test-agent", "test-mandate")
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
                Instant.parse("2026-10-01T00:00:00Z"),
                AgentContext.authenticatedAgent("test-agent", "test-mandate")
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
                Instant.parse("2026-10-01T00:00:00Z"),
                AgentContext.authenticatedAgent("test-agent", "test-mandate")
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
                Instant.parse("2026-09-01T00:00:00Z"),
                AgentContext.authenticatedAgent("test-agent", "test-mandate")
        );

        RefundDecision decision = evaluator.evaluate(request);

        assertEquals(RefundDecision.Status.DENIED, decision.status());
    }

    @Test
    void unevaluatedBudgetFailsClosedByDefault() {
        RefundPolicyEvaluator failClosed = new RefundPolicyEvaluator(
                new RefundPolicy(5_000, 50_000, 200_000, 30),
                Clock.fixed(
                        Instant.parse("2026-10-06T00:00:00Z"),
                        ZoneOffset.UTC
                ),
                new NoOpBudgetDecision()
        );

        RefundDecision decision = failClosed.evaluate(new RefundRequest(
                "CAPTURE-DEFAULT",
                3_500L,
                "USD",
                Instant.parse("2026-10-01T00:00:00Z"),
                AgentContext.authenticatedAgent("test-agent", "test-mandate")
        ));

        assertEquals(RefundState.DENIED, decision.state());
        assertEquals("DAILY_BUDGET_NOT_EVALUATED", decision.reason());
        assertEquals(
                BudgetDecisionResult.Status.NOT_EVALUATED,
                decision.budgetDecision().status()
        );
    }

    @Test
    void explicitDemoBudgetIsVisible() {
        RefundDecision decision = evaluator.evaluate(new RefundRequest(
                "CAPTURE-DEMO",
                3_500L,
                "USD",
                Instant.parse("2026-10-01T00:00:00Z"),
                AgentContext.authenticatedAgent("test-agent", "test-mandate")
        ));

        assertEquals(
                BudgetDecisionResult.Status.DEMO_NOT_ENFORCED,
                decision.budgetDecision().status()
        );
        assertEquals(
                "DAILY_BUDGET_NOT_ENFORCED_IN_EXPLICIT_DEMO_MODE",
                decision.budgetDecision().explanation()
        );
    }

    @Test
    void exceededBudgetAlwaysDenies() {
        RefundPolicyEvaluator exceededBudget = new RefundPolicyEvaluator(
                new RefundPolicy(5_000, 50_000, 200_000, 30),
                Clock.fixed(
                        Instant.parse("2026-10-06T00:00:00Z"),
                        ZoneOffset.UTC
                ),
                (request, policy) -> new BudgetDecisionResult(
                        BudgetDecisionResult.Status.EXCEEDED,
                        "DAILY_BUDGET_EXCEEDED"
                )
        );

        RefundDecision decision = exceededBudget.evaluate(new RefundRequest(
                "CAPTURE-EXCEEDED",
                3_500L,
                "USD",
                Instant.parse("2026-10-01T00:00:00Z"),
                AgentContext.authenticatedAgent("test-agent", "test-mandate")
        ));

        assertEquals(RefundState.DENIED, decision.state());
        assertEquals("DAILY_BUDGET_EXCEEDED", decision.reason());
    }
}
