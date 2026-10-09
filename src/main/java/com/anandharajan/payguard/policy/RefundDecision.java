package com.anandharajan.payguard.policy;

public record RefundDecision(
        RefundState state,
        String reason,
        PolicyTrace policyTrace,
        BudgetDecisionResult budgetDecision,
        String correlationId
) {
    public RefundDecision(Status status, String reason) {
        this(
                status.toState(),
                reason,
                new PolicyTrace(java.util.List.of()),
                BudgetDecisionResult.notEvaluated(),
                null
        );
    }

    public Status status() {
        return Status.fromState(state);
    }

    public enum Status {
        APPROVED,
        PENDING_APPROVAL,
        DENIED;

        private RefundState toState() {
            return switch (this) {
                case APPROVED -> RefundState.APPROVED;
                case PENDING_APPROVAL -> RefundState.PENDING_APPROVAL;
                case DENIED -> RefundState.DENIED;
            };
        }

        private static Status fromState(RefundState state) {
            return switch (state) {
                case APPROVED, IN_FLIGHT, EXECUTED -> APPROVED;
                case PENDING_APPROVAL -> PENDING_APPROVAL;
                case DENIED, FAILED, RECONCILIATION_REQUIRED -> DENIED;
            };
        }
    }
}
