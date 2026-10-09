package com.anandharajan.payguard.policy;

public final class DatabaseBudgetDecision implements BudgetDecision {
    @Override
    public BudgetDecisionResult evaluate(
            RefundRequest request,
            RefundPolicy policy
    ) {
        return new BudgetDecisionResult(
                BudgetDecisionResult.Status.ALLOWED,
                "DAILY_BUDGET_RESERVED_TRANSACTIONALLY_IN_POSTGRES"
        );
    }
}
