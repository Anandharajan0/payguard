package com.anandharajan.payguard.policy;

public record MerchantMandate(
        String mandateId,
        MerchantMandateStatus status
) {
    public boolean isActive() {
        return status == MerchantMandateStatus.ACTIVE;
    }
}
