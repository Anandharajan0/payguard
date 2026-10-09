package com.anandharajan.payguard.policy;

public final class DemoBudgetDecision implements BudgetDecision {
    @Override
    public BudgetDecisionResult evaluate(
            RefundRequest request,
            RefundPolicy policy
    ) {
        return BudgetDecisionResult.demoNotEnforced();
    }
}
