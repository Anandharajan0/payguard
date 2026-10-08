package com.anandharajan.payguard.policy;

import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class ApprovalStore {

    private final Map<String, ApprovalRequest> requests = new ConcurrentHashMap<>();

    public ApprovalRequest create(RefundRequest refundRequest) {
        String approvalId = UUID.randomUUID().toString();

        ApprovalRequest request = new ApprovalRequest(
                approvalId,
                refundRequest,
                java.time.Instant.now()
        );

        requests.put(approvalId, request);
        return request;
    }

    public ApprovalRequest get(String approvalId) {
        return requests.get(approvalId);
    }

    public ApprovalRequest remove(String approvalId) {
        return requests.remove(approvalId);
    }
}
