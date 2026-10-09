package com.anandharajan.payguard.policy;

public interface BudgetDecision {
    BudgetDecisionResult evaluate(RefundRequest request, RefundPolicy policy);
}
