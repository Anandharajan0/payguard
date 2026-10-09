package com.anandharajan.payguard.policy;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public final class RefundPolicyEvaluator {

    private final RefundPolicy policy;
    private final Clock clock;
    private final BudgetDecision budgetDecision;

    public RefundPolicyEvaluator(RefundPolicy policy, Clock clock) {
        this(policy, clock, new NoOpBudgetDecision());
    }

    public RefundPolicyEvaluator(
            RefundPolicy policy,
            Clock clock,
            BudgetDecision budgetDecision
    ) {
        this.policy = policy;
        this.clock = clock;
        this.budgetDecision = budgetDecision;
    }

    public RefundDecision evaluate(RefundRequest request) {
        String correlationId = UUID.randomUUID().toString();
        List<PolicyTraceEntry> trace = new ArrayList<>();
        List<String> errors = RefundValidation.validate(request, policy, clock);
        trace.add(new PolicyTraceEntry(
                "REQUEST_VALIDATION",
                errors.isEmpty(),
                errors.isEmpty() ? "Typed request is valid" : String.join(", ", errors)
        ));
        if (!errors.isEmpty()) {
            return decision(
                    RefundState.DENIED,
                    errors.getFirst(),
                    trace,
                    correlationId,
                    BudgetDecisionResult.notEvaluated()
            );
        }

        boolean authorized = request.agentContext() != null
                && request.agentContext().isAuthorized();
        trace.add(new PolicyTraceEntry(
                "AUTHENTICATED_AGENT_MANDATE",
                authorized,
                authorized
                        ? "Authenticated active merchant mandate"
                        : "Authenticated active merchant mandate required"
        ));
        if (!authorized) {
            return decision(
                    RefundState.DENIED,
                    "AGENT_NOT_AUTHORIZED",
                    trace,
                    correlationId,
                    BudgetDecisionResult.notEvaluated()
            );
        }

        BudgetDecisionResult budget = budgetDecision.evaluate(request, policy);
        trace.add(new PolicyTraceEntry(
                "DAILY_BUDGET",
                budgetPermitsContinuation(budget),
                budget.explanation()
        ));
        if (budget.status() == BudgetDecisionResult.Status.EXCEEDED) {
            return decision(
                    RefundState.DENIED,
                    "DAILY_BUDGET_EXCEEDED",
                    trace,
                    correlationId,
                    budget
            );
        }
        if (budget.status() == BudgetDecisionResult.Status.NOT_EVALUATED) {
            return decision(
                    RefundState.DENIED,
                    "DAILY_BUDGET_NOT_EVALUATED",
                    trace,
                    correlationId,
                    budget
            );
        }

        if (request.amountCents() <= policy.autoApproveMaxCents()) {
            trace.add(new PolicyTraceEntry(
                    "AUTO_APPROVAL_LIMIT",
                    true,
                    "Amount is within the automatic approval limit"
            ));
            return decision(
                    RefundState.APPROVED,
                    "WITHIN_AUTO_APPROVAL_LIMIT",
                    trace,
                    correlationId,
                    budget
            );
        }
        trace.add(new PolicyTraceEntry(
                "AUTO_APPROVAL_LIMIT",
                false,
                "Amount exceeds the automatic approval limit"
        ));

        if (request.amountCents() <= policy.humanApprovalMaxCents()) {
            trace.add(new PolicyTraceEntry(
                    "HUMAN_APPROVAL_LIMIT",
                    true,
                    "Amount requires human approval but is within the maximum"
            ));
            return decision(
                    RefundState.PENDING_APPROVAL,
                    "HUMAN_APPROVAL_REQUIRED",
                    trace,
                    correlationId,
                    budget
            );
        }
        trace.add(new PolicyTraceEntry(
                "HUMAN_APPROVAL_LIMIT",
                false,
                "Amount exceeds the maximum permitted refund"
        ));
        return decision(
                RefundState.DENIED,
                "EXCEEDS_HUMAN_APPROVAL_LIMIT",
                trace,
                correlationId,
                budget
        );
    }

    private RefundDecision decision(
            RefundState state,
            String reason,
            List<PolicyTraceEntry> trace,
            String correlationId,
            BudgetDecisionResult budget
    ) {
        return new RefundDecision(
                state,
                reason,
                new PolicyTrace(trace),
                budget,
                correlationId
        );
    }

    private boolean budgetPermitsContinuation(BudgetDecisionResult budget) {
        return budget.status() == BudgetDecisionResult.Status.ALLOWED
                || budget.status() == BudgetDecisionResult.Status.DEMO_NOT_ENFORCED;
    }
}
