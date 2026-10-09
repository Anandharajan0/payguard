package com.anandharajan.payguard.refund;

import com.anandharajan.payguard.policy.AgentContext;
import com.anandharajan.payguard.policy.ApproverContext;
import com.anandharajan.payguard.policy.RefundDecision;
import com.anandharajan.payguard.policy.RefundExecutionResult;
import com.anandharajan.payguard.policy.RefundRequest;
import com.anandharajan.payguard.policy.RefundService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/refunds")
public class RefundController {

    private final RefundService refundService;
    private final String mandateId;
    private final String approverMandateId;

    public RefundController(
            RefundService refundService,
            @Value("${payguard.security.mandate-id}") String mandateId,
            @Value("${payguard.security.approver-mandate-id}") String approverMandateId
    ) {
        this.refundService = refundService;
        this.mandateId = mandateId;
        this.approverMandateId = approverMandateId;
    }

    @PostMapping("/evaluate")
    public RefundDecision evaluate(
            @RequestBody RefundHttpRequest request,
            Authentication authentication
    ) {
        return refundService.evaluate(
                toDomain(request, authentication),
                authentication.getName()
        );
    }

    @PostMapping
    public RefundExecutionResult refund(
            @RequestBody RefundHttpRequest request,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            Authentication authentication
    ) {
        return refundService.refund(
                toDomain(request, authentication),
                idempotencyKey
        );
    }

    @PostMapping("/approvals/{approvalId}/approve")
    public RefundExecutionResult approve(
            @PathVariable String approvalId,
            Authentication authentication
    ) {
        return refundService.approve(
                approvalId,
                new ApproverContext(
                        authentication.getName(),
                        approverMandateId,
                        authentication.isAuthenticated()
                )
        );
    }

    private RefundRequest toDomain(
            RefundHttpRequest request,
            Authentication authentication
    ) {
        return new RefundRequest(
                request.captureId(),
                request.amountCents(),
                request.currency(),
                request.transactionCreatedAt(),
                AgentContext.authenticatedAgent(
                        authentication.getName(),
                        mandateId
                )
        );
    }
}
