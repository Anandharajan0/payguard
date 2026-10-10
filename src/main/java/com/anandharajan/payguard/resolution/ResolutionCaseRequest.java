package com.anandharajan.payguard.resolution;

public record ResolutionCaseRequest(
        String customerIssue,
        String captureId,
        String idempotencyKey
) {}
