package com.anandharajan.payguard.policy;

public record BudgetDecisionResult(
        Status status,
        String explanation
) {
    public enum Status {
        NOT_EVALUATED,
        DEMO_NOT_ENFORCED,
        ALLOWED,
        EXCEEDED
    }

    public static BudgetDecisionResult notEvaluated() {
        return new BudgetDecisionResult(
                Status.NOT_EVALUATED,
                "DAILY_BUDGET_NOT_ENFORCED_IN_SLICE_1"
        );
    }

    public static BudgetDecisionResult demoNotEnforced() {
        return new BudgetDecisionResult(
                Status.DEMO_NOT_ENFORCED,
                "DAILY_BUDGET_NOT_ENFORCED_IN_EXPLICIT_DEMO_MODE"
        );
    }
}
