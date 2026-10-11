package com.anandharajan.payguard.resolution;

public record ResolutionCaseClaim(
        ResolutionCase resolutionCase,
        boolean claimed,
        String claimToken
) {}
