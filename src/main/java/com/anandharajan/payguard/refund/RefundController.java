package com.anandharajan.payguard.refund;

import com.anandharajan.payguard.policy.RefundExecutionResult;
import com.anandharajan.payguard.policy.RefundRequest;
import com.anandharajan.payguard.policy.RefundService;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/refunds")
public class RefundController {

    private final RefundService refundService;

    public RefundController(RefundService refundService) {
        this.refundService = refundService;
    }

    @PostMapping("/evaluate")
    public Object evaluate(@RequestBody RefundRequest request) {
        return refundService.evaluate(request);
    }

    @PostMapping
    public RefundExecutionResult refund(@RequestBody RefundRequest request) {
        return refundService.refund(request);
    }

    @PostMapping("/approvals/{approvalId}/approve")
    public RefundExecutionResult approve(
            @PathVariable String approvalId
    ) {
        return refundService.approve(approvalId);
    }
}
