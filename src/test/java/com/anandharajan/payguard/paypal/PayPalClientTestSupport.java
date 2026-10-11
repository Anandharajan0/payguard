package com.anandharajan.payguard.paypal;

import org.springframework.web.client.RestClient;

public final class PayPalClientTestSupport {

    private PayPalClientTestSupport() {}

    public static PayPalClient withTransport(RestClient client) {
        return new PayPalClient(client, "test-client", "test-secret");
    }
}
