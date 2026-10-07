package com.anandharajan.payguard.policy;

import com.anandharajan.payguard.paypal.PayPalClient;
import tools.jackson.databind.JsonNode;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.util.UUID;

@Service
public class RefundService {

    private final RefundPolicyEvaluator evaluator;
    private final PayPalClient payPalClient;

    public RefundService(PayPalClient payPalClient) {
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

        this.payPalClient = payPalClient;
    }

    public RefundDecision evaluate(RefundRequest request) {
        return evaluator.evaluate(request);
    }

    public RefundExecutionResult refund(RefundRequest request) {
        RefundDecision decision = evaluator.evaluate(request);

        if (decision.status() != RefundDecision.Status.APPROVED) {
            return new RefundExecutionResult(decision, null, null);
        }

        String requestId = UUID.randomUUID().toString();

        JsonNode response = payPalClient.refund(
                request.captureId(),
                request.amountCents(),
                request.currency(),
                requestId
        );

        return new RefundExecutionResult(
                decision,
                response.path("id").asText(null),
                response.path("status").asText(null)
        );
    }
}
