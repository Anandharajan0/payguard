package com.anandharajan.payguard.policy;

import org.springframework.stereotype.Component;

@Component
public class NoOpBudgetDecision implements BudgetDecision {
    @Override
    public BudgetDecisionResult evaluate(
            RefundRequest request,
            RefundPolicy policy
    ) {
        return BudgetDecisionResult.notEvaluated();
    }
}
