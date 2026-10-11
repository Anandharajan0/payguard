package com.anandharajan.payguard.paypal;

import tools.jackson.databind.JsonNode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import java.math.BigDecimal;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.http.HttpClient;
import java.util.Map;

@Component
public class PayPalClient implements PayPalRefundGateway {

    private static final String SANDBOX_ORIGIN =
            "https://api-m.sandbox.paypal.com";
    private static final String LIVE_ORIGIN = "https://api-m.paypal.com";

    private final RestClient client;
    private final String clientId;
    private final String clientSecret;
    private final PayPalRefundOutcomeMapper outcomeMapper =
            new PayPalRefundOutcomeMapper();

    @Autowired
    public PayPalClient(
            @Value("${paypal.base-url}") String baseUrl,
            @Value("${paypal.client-id}") String clientId,
            @Value("${paypal.client-secret}") String clientSecret
    ) {
        this(createClient(baseUrl),
                clientId, clientSecret);
    }

    PayPalClient(
            RestClient client,
            String clientId,
            String clientSecret
    ) {
        this.client = client;
        this.clientId = clientId;
        this.clientSecret = clientSecret;
    }

    public JsonNode refund(
            String captureId,
            long amountCents,
            String currency,
            String requestId
    ) {
        String token;
        try {
            token = getAccessToken();
        } catch (RuntimeException exception) {
            throw new PayPalRefundException(
                    outcomeMapper.mapNotDispatched()
            );
        }

        String amount = BigDecimal.valueOf(amountCents, 2).toPlainString();

        try {
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
                    .onStatus(status -> !status.is2xxSuccessful(),
                            (request, response) -> {
                                throw new PayPalRefundException(
                                        outcomeMapper.mapHttpFailure(
                                                response.getStatusCode().value()
                                        )
                                );
                            })
                    .body(JsonNode.class);
        } catch (RestClientResponseException exception) {
            throw new PayPalRefundException(outcomeMapper.mapHttpFailure(
                    exception.getStatusCode().value()
            ));
        } catch (RestClientException exception) {
            throw new PayPalRefundException(
                    outcomeMapper.mapAmbiguousTransportFailure()
            );
        }
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
                .onStatus(status -> !status.is2xxSuccessful(),
                        (request, errorResponse) -> {
                            throw new IllegalStateException(
                                    "PayPal OAuth endpoint returned HTTP "
                                            + errorResponse.getStatusCode().value()
                            );
                        })
                .body(JsonNode.class);
        String token = response == null
                ? null
                : response.path("access_token").asText(null);
        if (token == null || token.isBlank()) {
            throw new IllegalStateException(
                    "PayPal access token response was incomplete"
            );
        }
        return token;
    }

    private static RestClient createClient(String baseUrl) {
        URI origin = validateBaseUrl(baseUrl);
        return RestClient.builder()
                .baseUrl(origin.toString())
                .requestFactory(redirectSafeRequestFactory())
                .build();
    }

    static JdkClientHttpRequestFactory redirectSafeRequestFactory() {
        return new JdkClientHttpRequestFactory(
                HttpClient.newBuilder()
                        .followRedirects(HttpClient.Redirect.NEVER)
                        .build()
        );
    }

    private static URI validateBaseUrl(String baseUrl) {
        final URI uri;
        try {
            uri = new URI(baseUrl);
        } catch (NullPointerException | URISyntaxException exception) {
            throw invalidBaseUrl();
        }
        String scheme = uri.getScheme();
        String host = uri.getHost();
        boolean supportedHost = "api-m.sandbox.paypal.com".equals(host)
                || "api-m.paypal.com".equals(host);
        String rawPath = uri.getRawPath();
        boolean supportedPath = rawPath == null
                || rawPath.isEmpty()
                || "/".equals(rawPath);
        String rawAuthority = uri.getRawAuthority();
        boolean canonicalAuthority = host != null
                && (host.equals(rawAuthority)
                || (host + ":443").equals(rawAuthority));
        if (!"https".equals(scheme)
                || !supportedHost
                || !canonicalAuthority
                || (uri.getPort() != -1 && uri.getPort() != 443)
                || uri.getRawUserInfo() != null
                || !supportedPath
                || uri.getRawQuery() != null
                || uri.getRawFragment() != null) {
            throw invalidBaseUrl();
        }
        return URI.create(
                "api-m.sandbox.paypal.com".equals(host)
                        ? SANDBOX_ORIGIN : LIVE_ORIGIN
        );
    }

    private static IllegalArgumentException invalidBaseUrl() {
        return new IllegalArgumentException(
                "paypal.base-url must be the canonical HTTPS PayPal Sandbox "
                        + "or Live API origin"
        );
    }
}
