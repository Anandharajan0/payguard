package com.anandharajan.payguard.policy;

public record RefundPolicy(
        long autoApproveMaxCents,
        long humanApprovalMaxCents,
        long dailyLimitCents,
        int maxTransactionAgeDays
) {}
