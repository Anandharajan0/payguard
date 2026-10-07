package com.anandharajan.payguard.policy;

import org.springframework.stereotype.Service;

import java.time.Clock;

@Service
public class RefundService {

    private final RefundPolicyEvaluator evaluator;

    public RefundService() {
        RefundPolicy policy = new RefundPolicy(
                5_000,
                50_000,
                200_000,
                30
        );

        this.evaluator = new RefundPolicyEvaluator(
                policy,
                Clock.systemUTC()
        );
    }

    public RefundDecision evaluate(RefundRequest request) {
        return evaluator.evaluate(request);
    }
}
