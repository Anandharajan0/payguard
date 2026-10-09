package com.anandharajan.payguard.paypal;

import tools.jackson.databind.JsonNode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.util.Map;

@Component
public class PayPalClient implements PayPalRefundGateway {

    private final RestClient client;
    private final String clientId;
    private final String clientSecret;

    public PayPalClient(
            @Value("${paypal.base-url}") String baseUrl,
            @Value("${paypal.client-id}") String clientId,
            @Value("${paypal.client-secret}") String clientSecret
    ) {
        this.client = RestClient.builder()
                .baseUrl(baseUrl)
                .build();
        this.clientId = clientId;
        this.clientSecret = clientSecret;
    }

    public JsonNode refund(
            String captureId,
            long amountCents,
            String currency,
            String requestId
    ) {
        String token = getAccessToken();

        String amount = BigDecimal.valueOf(amountCents, 2).toPlainString();

        return client.post()
                .uri("/v2/payments/captures/{captureId}/refund", captureId)
                .headers(headers -> {
                    headers.setBearerAuth(token);
                    headers.setContentType(MediaType.APPLICATION_JSON);
                    headers.set("PayPal-Request-Id", requestId);
                })
                .body(Map.of(
                        "amount", Map.of(
                                "value", amount,
                                "currency_code", currency
                        )
                ))
                .retrieve()
                .body(JsonNode.class);
    }

    private String getAccessToken() {
        JsonNode response = client.post()
                .uri("/v1/oauth2/token")
                .headers(headers -> {
                    headers.setBasicAuth(clientId, clientSecret);
                    headers.setContentType(
                            MediaType.APPLICATION_FORM_URLENCODED
                    );
                })
                .body("grant_type=client_credentials")
                .retrieve()
                .body(JsonNode.class);

        return response.get("access_token").asText();
    }
}
