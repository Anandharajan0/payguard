package com.anandharajan.payguard.paypal;

import com.anandharajan.payguard.policy.RefundState;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;

class PayPalClientTest {

    private static final String BASE_URL =
            "https://api-m.sandbox.paypal.com";

    @Test
    void everyStructuredKnown422RequiresReconciliation() {
        List<String> issues = List.of(
                "REFUND_AMOUNT_EXCEEDED",
                "CAPTURE_NOT_REFUNDABLE",
                "CURRENCY_MISMATCH"
        );
        for (String issue : issues) {
            ClientFixture fixture = clientFixture();
            expectToken(fixture.server());
            fixture.server().expect(requestTo(BASE_URL
                            + "/v2/payments/captures/CAPTURE-1/refund"))
                    .andRespond(withStatus(HttpStatus.UNPROCESSABLE_ENTITY)
                            .contentType(MediaType.APPLICATION_JSON)
                            .body("""
                                    {
                                      "name": "UNPROCESSABLE_ENTITY",
                                      "debug_id": "provider-debug-1",
                                      "details": [{"issue": "%s"}]
                                    }
                                    """.formatted(issue)));

            PayPalRefundException failure = assertThrows(
                    PayPalRefundException.class,
                    () -> fixture.client().refund(
                            "CAPTURE-1", 3_500, "USD", "operation-1"
                    )
            );

            assertEquals(
                    RefundState.RECONCILIATION_REQUIRED,
                    failure.outcome().state()
            );
            fixture.server().verify();
        }
    }

    @Test
    void malformedAndIntermediary422BodiesRemainAmbiguous() {
        for (String body : List.of(
                "{malformed",
                "<html>gateway failure</html>",
                """
                {"name":"UNPROCESSABLE_ENTITY","debug_id":"x",
                 "details":[{"issue":"REFUND_AMOUNT_EXCEEDED"}]}
                """
        )) {
            ClientFixture fixture = clientFixture();
            expectToken(fixture.server());
            fixture.server().expect(requestTo(BASE_URL
                            + "/v2/payments/captures/CAPTURE-1/refund"))
                    .andRespond(withStatus(HttpStatus.UNPROCESSABLE_ENTITY)
                            .contentType(MediaType.TEXT_PLAIN)
                            .body(body));

            PayPalRefundException failure = assertThrows(
                    PayPalRefundException.class,
                    () -> fixture.client().refund(
                            "CAPTURE-1", 3_500, "USD", "operation-1"
                    )
            );

            assertEquals(
                    RefundState.RECONCILIATION_REQUIRED,
                    failure.outcome().state()
            );
            fixture.server().verify();
        }
    }

    @Test
    void unknownFourHundredResponseRequiresReconciliation() {
        ClientFixture fixture = clientFixture();
        expectToken(fixture.server());
        fixture.server().expect(requestTo(BASE_URL
                        + "/v2/payments/captures/CAPTURE-1/refund"))
                .andRespond(withStatus(HttpStatus.BAD_REQUEST)
                        .contentType(MediaType.TEXT_PLAIN)
                        .body("gateway rejected request"));

        PayPalRefundException failure = assertThrows(
                PayPalRefundException.class,
                () -> fixture.client().refund(
                        "CAPTURE-1", 3_500, "USD", "operation-1"
                )
        );

        assertEquals(
                RefundState.RECONCILIATION_REQUIRED,
                failure.outcome().state()
        );
        fixture.server().verify();
    }

    @Test
    void tokenAcquisitionFailureProvesRefundWasNotDispatched() {
        ClientFixture fixture = clientFixture();
        fixture.server().expect(requestTo(BASE_URL + "/v1/oauth2/token"))
                .andRespond(withStatus(HttpStatus.UNAUTHORIZED)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("{\"error\":\"invalid_client\"}"));

        PayPalRefundException failure = assertThrows(
                PayPalRefundException.class,
                () -> fixture.client().refund(
                        "CAPTURE-1", 3_500, "USD", "operation-1"
                )
        );

        assertEquals(RefundState.FAILED, failure.outcome().state());
        assertEquals("PAYPAL_TOKEN_UNAVAILABLE", failure.outcome().error());
        fixture.server().verify();
    }

    @Test
    void redirectResponsesAreNeverForwardedForOAuthOrRefund() throws Exception {
        AtomicInteger oauthTargetCalls = new AtomicInteger();
        AtomicInteger refundTargetCalls = new AtomicInteger();
        AtomicInteger refundCalls = new AtomicInteger();
        HttpServer server = HttpServer.create(
                new InetSocketAddress("127.0.0.1", 0), 0
        );
        server.createContext("/v1/oauth2/token", exchange ->
                redirect(exchange, "/oauth-target"));
        server.createContext("/oauth-target", exchange -> {
            oauthTargetCalls.incrementAndGet();
            respond(exchange, 200, "{\"access_token\":\"unsafe\"}");
        });
        server.createContext(
                "/v2/payments/captures/CAPTURE-1/refund",
                exchange -> {
                    refundCalls.incrementAndGet();
                    redirect(exchange, "/refund-target");
                }
        );
        server.createContext("/refund-target", exchange -> {
            refundTargetCalls.incrementAndGet();
            respond(exchange, 200, "{\"id\":\"REFUND-UNSAFE\","
                    + "\"status\":\"COMPLETED\"}");
        });
        server.start();
        try {
            String localUrl = "http://127.0.0.1:"
                    + server.getAddress().getPort();
            PayPalClient client = new PayPalClient(
                    RestClient.builder()
                            .baseUrl(localUrl)
                            .requestFactory(
                                    PayPalClient.redirectSafeRequestFactory()
                            )
                            .build(),
                    "test-client",
                    "test-secret"
            );

            PayPalRefundException oauthRedirect = assertThrows(
                    PayPalRefundException.class,
                    () -> client.refund(
                            "CAPTURE-1", 3_500, "USD", "operation-1"
                    )
            );
            assertEquals(RefundState.FAILED, oauthRedirect.outcome().state());
            assertEquals(0, oauthTargetCalls.get());
            assertEquals(0, refundCalls.get());

            HttpServer refundServer = HttpServer.create(
                    new InetSocketAddress("127.0.0.1", 0), 0
            );
            refundServer.createContext("/v1/oauth2/token", exchange ->
                    respond(exchange, 200, "{\"access_token\":\"test-token\"}"));
            refundServer.createContext(
                    "/v2/payments/captures/CAPTURE-1/refund",
                    exchange -> {
                        refundCalls.incrementAndGet();
                        redirect(exchange, "/refund-target");
                    }
            );
            refundServer.createContext("/refund-target", exchange -> {
                refundTargetCalls.incrementAndGet();
                respond(exchange, 200, "{\"id\":\"REFUND-UNSAFE\","
                        + "\"status\":\"COMPLETED\"}");
            });
            refundServer.start();
            try {
                PayPalClient refundRedirectClient = new PayPalClient(
                        localClient(refundServer.getAddress().getPort()),
                        "test-client",
                        "test-secret"
                );
                PayPalRefundException refundRedirect = assertThrows(
                        PayPalRefundException.class,
                        () -> refundRedirectClient.refund(
                                "CAPTURE-1", 3_500, "USD", "operation-1"
                        )
                );
                assertEquals(
                        RefundState.RECONCILIATION_REQUIRED,
                        refundRedirect.outcome().state()
                );
                assertEquals(1, refundCalls.get());
                assertEquals(0, refundTargetCalls.get());
            } finally {
                refundServer.stop(0);
            }
        } finally {
            server.stop(0);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "http://api-m.sandbox.paypal.com",
            "https://api-m.sandbox.paypal.com.attacker.example",
            "https://api-m.paypal.com.attacker.example",
            "https://user@api-m.sandbox.paypal.com",
            "https://api-m.sandbox.paypal.com:444",
            "https://api-m.sandbox.paypal.com/v1",
            "https://api-m.sandbox.paypal.com?next=/refund",
            "https://api-m.sandbox.paypal.com#fragment"
    })
    void rejectsUnapprovedEndpointConfigurationsBeforeDispatch(String url) {
        assertThrows(
                IllegalArgumentException.class,
                () -> new PayPalClient(url, "test-client", "test-secret")
        );
    }

    @Test
    void failureShapedResponseCannotBeObtainedFromAnUnapprovedOrigin() {
        AtomicInteger unapprovedEndpointCalls = new AtomicInteger();
        HttpServer server;
        try {
            server = HttpServer.create(
                    new InetSocketAddress("127.0.0.1", 0), 0
            );
        } catch (IOException exception) {
            throw new IllegalStateException(exception);
        }
        server.createContext("/v1/oauth2/token", exchange -> {
            unapprovedEndpointCalls.incrementAndGet();
            respond(exchange, 200, "{\"access_token\":\"test-token\"}");
        });
        server.createContext(
                "/v2/payments/captures/CAPTURE-1/refund",
                exchange -> {
                    unapprovedEndpointCalls.incrementAndGet();
                    respond(exchange, 200,
                            "{\"id\":\"REFUND-UNTRUSTED\","
                                    + "\"status\":\"FAILED\"}");
                }
        );
        server.start();
        try {
            String unapprovedUrl = "http://127.0.0.1:"
                    + server.getAddress().getPort();
            assertThrows(
                    IllegalArgumentException.class,
                    () -> new PayPalClient(
                            unapprovedUrl,
                            "test-client",
                            "test-secret"
                    )
            );
            assertEquals(0, unapprovedEndpointCalls.get());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void rejectsMalformedCanonicalAuthorityConfigurations() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new PayPalClient(
                        "https://api-m.sandbox.paypal.com:",
                        "test-client", "test-secret"
                )
        );
    }

    @Test
    void permitsOnlyCanonicalSandboxAndLiveOriginsAndRootSlash() {
        for (String url : List.of(
                "https://api-m.sandbox.paypal.com",
                "https://api-m.sandbox.paypal.com/",
                "https://api-m.paypal.com",
                "https://api-m.paypal.com:443/"
        )) {
            new PayPalClient(url, "test-client", "test-secret");
        }
    }

    @Test
    void validCompletedRefundResponseRemainsExecuted() {
        ClientFixture fixture = clientFixture();
        expectToken(fixture.server());
        fixture.server().expect(requestTo(BASE_URL
                        + "/v2/payments/captures/CAPTURE-1/refund"))
                .andRespond(withSuccess(
                        "{\"id\":\"REFUND-1\",\"status\":\"COMPLETED\"}",
                        MediaType.APPLICATION_JSON
                ));

        var response = fixture.client().refund(
                "CAPTURE-1", 3_500, "USD", "operation-1"
        );
        PayPalRefundOutcome outcome =
                new PayPalRefundOutcomeMapper().map(response);

        assertEquals(RefundState.EXECUTED, outcome.state());
        assertEquals("REFUND-1", outcome.refundId());
        fixture.server().verify();
    }

    @Test
    void transportFailureAfterRefundDispatchRequiresReconciliation() {
        ClientFixture fixture = clientFixture();
        expectToken(fixture.server());
        fixture.server().expect(requestTo(BASE_URL
                        + "/v2/payments/captures/CAPTURE-1/refund"))
                .andRespond(withException(new IOException("connection reset")));

        PayPalRefundException failure = assertThrows(
                PayPalRefundException.class,
                () -> fixture.client().refund(
                        "CAPTURE-1", 3_500, "USD", "operation-1"
                )
        );

        assertEquals(
                RefundState.RECONCILIATION_REQUIRED,
                failure.outcome().state()
        );
        fixture.server().verify();
    }

    @Test
    void refundTimeoutRequiresReconciliation() {
        ClientFixture fixture = clientFixture();
        expectToken(fixture.server());
        fixture.server().expect(requestTo(BASE_URL
                        + "/v2/payments/captures/CAPTURE-1/refund"))
                .andRespond(withException(new java.net.SocketTimeoutException(
                        "refund response timed out"
                )));

        PayPalRefundException failure = assertThrows(
                PayPalRefundException.class,
                () -> fixture.client().refund(
                        "CAPTURE-1", 3_500, "USD", "operation-1"
                )
        );

        assertEquals(
                RefundState.RECONCILIATION_REQUIRED,
                failure.outcome().state()
        );
        fixture.server().verify();
    }

    @Test
    void malformedSuccessfulRefundResponseRequiresReconciliation() {
        ClientFixture fixture = clientFixture();
        expectToken(fixture.server());
        fixture.server().expect(requestTo(BASE_URL
                        + "/v2/payments/captures/CAPTURE-1/refund"))
                .andRespond(withSuccess("{malformed", MediaType.APPLICATION_JSON));

        PayPalRefundException failure = assertThrows(
                PayPalRefundException.class,
                () -> fixture.client().refund(
                        "CAPTURE-1", 3_500, "USD", "operation-1"
                )
        );

        assertEquals(
                RefundState.RECONCILIATION_REQUIRED,
                failure.outcome().state()
        );
        fixture.server().verify();
    }

    @Test
    void typeInvalidSuccessfulBodiesCannotBecomeProviderOutcomes() {
        for (String body : List.of(
                "{\"id\":123,\"status\":\"FAILED\"}",
                "{\"id\":true,\"status\":\"FAILED\"}",
                "{\"id\":[],\"status\":\"FAILED\"}",
                "{\"id\":{},\"status\":\"FAILED\"}",
                "{\"id\":\"CAPTURE-123\",\"status\":123}",
                "{\"id\":\"CAPTURE-123\",\"status\":false}",
                "{\"id\":null,\"status\":\"COMPLETED\"}",
                "{\"status\":\"COMPLETED\"}",
                "{\"id\":\"\",\"status\":\"COMPLETED\"}",
                "{\"id\":\"REFUND-1\",\"status\":\"PROCESSING\"}"
        )) {
            ClientFixture fixture = clientFixture();
            expectToken(fixture.server());
            fixture.server().expect(requestTo(BASE_URL
                            + "/v2/payments/captures/CAPTURE-1/refund"))
                    .andRespond(withSuccess(
                            body, MediaType.APPLICATION_JSON
                    ));

            var response = fixture.client().refund(
                    "CAPTURE-1", 3_500, "USD", "operation-1"
            );
            PayPalRefundOutcome outcome =
                    new PayPalRefundOutcomeMapper().map(response);

            assertEquals(
                    RefundState.RECONCILIATION_REQUIRED,
                    outcome.state(),
                    body
            );
            fixture.server().verify();
        }
    }

    @Test
    void serverErrorRequiresReconciliation() {
        ClientFixture fixture = clientFixture();
        expectToken(fixture.server());
        fixture.server().expect(requestTo(BASE_URL
                        + "/v2/payments/captures/CAPTURE-1/refund"))
                .andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("{\"message\":\"temporary failure\"}"));

        PayPalRefundException failure = assertThrows(
                PayPalRefundException.class,
                () -> fixture.client().refund(
                        "CAPTURE-1", 3_500, "USD", "operation-1"
                )
        );

        assertEquals(
                RefundState.RECONCILIATION_REQUIRED,
                failure.outcome().state()
        );
        fixture.server().verify();
    }

    private static ClientFixture clientFixture() {
        RestClient.Builder builder = RestClient.builder().baseUrl(BASE_URL);
        MockRestServiceServer server =
                MockRestServiceServer.bindTo(builder).build();
        return new ClientFixture(
                new PayPalClient(builder.build(), "test-client", "test-secret"),
                server
        );
    }

    private static RestClient localClient(int port) {
        return RestClient.builder()
                .baseUrl("http://127.0.0.1:" + port)
                .requestFactory(PayPalClient.redirectSafeRequestFactory())
                .build();
    }

    private static void expectToken(MockRestServiceServer server) {
        server.expect(requestTo(BASE_URL + "/v1/oauth2/token"))
                .andRespond(withSuccess(
                        "{\"access_token\":\"test-token\"}",
                        MediaType.APPLICATION_JSON
                ));
    }

    private static void redirect(HttpExchange exchange, String target)
            throws IOException {
        exchange.getResponseHeaders().set("Location", target);
        respond(
                exchange,
                302,
                "{\"id\":\"REFUND-UNSAFE\",\"status\":\"FAILED\"}"
        );
    }

    private static void respond(
            HttpExchange exchange,
            int status,
            String body
    ) throws IOException {
        byte[] bytes = body.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set(
                "Content-Type", MediaType.APPLICATION_JSON_VALUE
        );
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    private record ClientFixture(
            PayPalClient client,
            MockRestServiceServer server
    ) {}
}
