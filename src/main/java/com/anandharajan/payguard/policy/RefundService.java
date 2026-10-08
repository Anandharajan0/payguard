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
    private final ApprovalStore approvalStore;

    public RefundService(
            PayPalClient payPalClient,
            ApprovalStore approvalStore
    ) {
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
        this.approvalStore = approvalStore;
    }

    public RefundDecision evaluate(RefundRequest request) {
        return evaluator.evaluate(request);
    }

    public RefundExecutionResult refund(RefundRequest request) {
        RefundDecision decision = evaluator.evaluate(request);

        if (decision.status() == RefundDecision.Status.DENIED) {
            return new RefundExecutionResult(
                    decision,
                    null,
                    null,
                    null
            );
        }

        if (decision.status() == RefundDecision.Status.PENDING_APPROVAL) {
            ApprovalRequest approval = approvalStore.create(request);

            return new RefundExecutionResult(
                    decision,
                    null,
                    null,
                    approval.approvalId()
            );
        }

        return executeRefund(request, decision, null);
    }

    public RefundExecutionResult approve(String approvalId) {
        ApprovalRequest approval = approvalStore.remove(approvalId);

        if (approval == null) {
            RefundDecision decision = new RefundDecision(
                    RefundDecision.Status.DENIED,
                    "APPROVAL_NOT_FOUND"
            );

            return new RefundExecutionResult(
                    decision,
                    null,
                    null,
                    approvalId
            );
        }

        RefundDecision decision = evaluator.evaluate(
                approval.refundRequest()
        );

        if (decision.status() != RefundDecision.Status.APPROVED) {
            return new RefundExecutionResult(
                    decision,
                    null,
                    null,
                    approvalId
            );
        }

        return executeRefund(
                approval.refundRequest(),
                decision,
                approvalId
        );
    }

    private RefundExecutionResult executeRefund(
            RefundRequest request,
            RefundDecision decision,
            String approvalId
    ) {
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
                response.path("status").asText(null),
                approvalId
        );
    }
}
