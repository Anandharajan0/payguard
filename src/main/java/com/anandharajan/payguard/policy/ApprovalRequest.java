package com.anandharajan.payguard.policy;

import java.time.Instant;

public record ApprovalRequest(
        String approvalId,
        RefundRequest refundRequest,
        Instant createdAt
) {}
