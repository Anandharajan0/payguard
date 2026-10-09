package com.anandharajan.payguard.policy;

public record ApproverContext(
        String approverId,
        String mandateId,
        boolean authenticated
) {
    public boolean isAuthorizedFor(String operationMandateId) {
        return authenticated
                && approverId != null
                && !approverId.isBlank()
                && mandateId != null
                && !mandateId.isBlank()
                && mandateId.equals(operationMandateId);
    }
}
