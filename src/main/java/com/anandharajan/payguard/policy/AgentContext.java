package com.anandharajan.payguard.policy;

public record AgentContext(
        String agentId,
        boolean authenticated,
        MerchantMandate merchantMandate
) {
    public static AgentContext authenticatedAgent(
            String agentId,
            String mandateId
    ) {
        return new AgentContext(
                agentId,
                true,
                new MerchantMandate(
                        mandateId,
                        MerchantMandateStatus.ACTIVE
                )
        );
    }

    public boolean isAuthorized() {
        return authenticated
                && agentId != null
                && !agentId.isBlank()
                && merchantMandate != null
                && merchantMandate.isActive();
    }
}
