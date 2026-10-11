package com.anandharajan.payguard.resolution;

import com.anandharajan.payguard.paypal.PayPalRefundGateway;
import com.anandharajan.payguard.policy.DemoBudgetDecision;
import com.anandharajan.payguard.policy.CaptureRefundConflictException;
import com.anandharajan.payguard.policy.RefundOperation;
import com.anandharajan.payguard.policy.RefundOperationStore;
import com.anandharajan.payguard.policy.RefundService;
import com.anandharajan.payguard.policy.RefundState;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
import static org.springframework.http.HttpMethod.POST;
import static org.springframework.http.HttpStatus.INTERNAL_SERVER_ERROR;

class HostedPaymentResolutionAgentTest {

    private static final String ENDPOINT = "https://model.example.test";
    private static final String VALID_OUTPUT = """
            {
              "action":"PROPOSE_REFUND",
              "suggestedAmountCents":3500,
              "confidence":0.94,
              "explanation":"The issue and capture evidence support review.",
              "evidence":["capture is completed"],
              "missingInformation":[]
            }
            """;
    private static final TransactionEvidence EVIDENCE =
            new TransactionEvidence(
                    "CAPTURE-35",
                    3_500,
                    "USD",
                    Instant.parse("2026-10-01T00:00:00Z"),
                    "payguard-demo-mandate",
                    "COMPLETED",
                    3_500,
                    List.of(),
                    "fixture-v1-CAPTURE-35",
                    Instant.parse("2026-10-10T00:00:00Z")
            );

    @Test
    void constructsAuthenticatedBoundedRequestAndParsesStructuredProposal() {
        RestClient.Builder builder = RestClient.builder().baseUrl(ENDPOINT);
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder)
                .build();
        HostedPaymentResolutionAgent agent = new HostedPaymentResolutionAgent(
                builder.build(), "server-secret", "test-model"
        );
        server.expect(requestTo(ENDPOINT + "/v1/chat/completions"))
                .andExpect(method(POST))
                .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer server-secret"))
                .andExpect(content().json("""
                        {
                          "model":"test-model",
                          "temperature":0,
                          "response_format":{"type":"json_object"},
                          "messages":[
                            {"role":"system","content":"You classify a customer payment issue for a financial firewall.\\nReturn JSON only with action, suggestedAmountCents, confidence,\\nexplanation, evidence, and missingInformation.\\nYou may propose a refund or request more information.\\nNever invent capture IDs, amounts, currency, mandate, authority,\\nprovider status, or approval. Trusted evidence is authoritative.\\n"},
                            {"role":"user","content":"Customer issue:\\nCustomer reports a duplicate transaction\\n\\nTrusted evidence:\\ncaptureId=CAPTURE-35, originalAmountCents=3500, remainingRefundableCents=3500,\\ncurrency=USD, captureStatus=COMPLETED, priorRefundCount=0\\n"}
                          ]
                        }
                        """))
                .andRespond(withSuccess(
                        envelope(VALID_OUTPUT), MediaType.APPLICATION_JSON
                ));

        ResolutionProposal proposal = agent.propose(
                "Customer reports a duplicate transaction", EVIDENCE
        );

        assertEquals("PROPOSE_REFUND", proposal.action());
        assertEquals(3_500, proposal.proposedAmountCents());
        assertEquals("CAPTURE-35", proposal.captureId());
        assertEquals("hosted:test-model", proposal.agentVersion());
        server.verify();
    }

    @Test
    void rejectsMalformedJsonMissingFieldsUnknownFieldsAndUnsafeValues() {
        assertRejected("{");
        assertRejected("""
                {"action":"PROPOSE_REFUND"}
                """);
        assertRejected(VALID_OUTPUT.replace(
                "\"missingInformation\":[]",
                "\"missingInformation\":[],\"captureId\":\"ATTACKER\""
        ));
        assertRejected(VALID_OUTPUT.replace(
                "\"action\":\"PROPOSE_REFUND\"",
                "\"action\":\"EXECUTE_REFUND\""
        ));
        assertRejected(VALID_OUTPUT.replace("\"confidence\":0.94",
                "\"confidence\":2.0"));
        assertRejected(VALID_OUTPUT.replace(
                "\"suggestedAmountCents\":3500",
                "\"suggestedAmountCents\":3501"
        ));
    }

    @Test
    void providerErrorsAreNotConvertedToSuccessfulProposals() {
        RestClient.Builder builder = RestClient.builder().baseUrl(ENDPOINT);
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder)
                .build();
        HostedPaymentResolutionAgent agent = new HostedPaymentResolutionAgent(
                builder.build(), "server-secret", "test-model"
        );
        server.expect(requestTo(ENDPOINT + "/v1/chat/completions"))
                .andRespond(withStatus(INTERNAL_SERVER_ERROR));

        assertThrows(RuntimeException.class, () -> agent.propose(
                "Customer reports a duplicate transaction", EVIDENCE
        ));
        server.verify();
    }

    @Test
    void invalidHostedProposalCannotCreateOrExecuteRefund() {
        RestClient.Builder builder = RestClient.builder().baseUrl(ENDPOINT);
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder)
                .build();
        HostedPaymentResolutionAgent agent = new HostedPaymentResolutionAgent(
                builder.build(), "server-secret", "test-model"
        );
        server.expect(requestTo(ENDPOINT + "/v1/chat/completions"))
                .andRespond(withSuccess(
                        envelope(VALID_OUTPUT.replace(
                                "\"suggestedAmountCents\":3500",
                                "\"suggestedAmountCents\":3501"
                        )),
                        MediaType.APPLICATION_JSON
                ));
        PayPalRefundGateway gateway = mock(PayPalRefundGateway.class);
        ResolutionService resolution = new ResolutionService(
                captureId -> EVIDENCE,
                agent,
                new RefundService(
                        gateway,
                        new RefundOperationStore(java.time.Clock.systemUTC()),
                        new DemoBudgetDecision(),
                        event -> {},
                        java.time.Clock.systemUTC()
                ),
                new InMemoryResolutionCaseRepository(),
                java.time.Clock.systemUTC(),
                EVIDENCE.mandateId()
        );

        ResolutionCaseResponse response = resolution.resolve(
                new ResolutionCaseRequest(
                        "Customer reports a duplicate charge",
                        EVIDENCE.captureId(),
                        "hosted-invalid-proposal-key"
                ),
                "support-agent"
        );
        assertEquals("PROPOSAL_REJECTED", response.status());
        assertEquals("MODEL_OUTPUT_REJECTED", response.decision().reason());
        assertTrue(response.execution() == null);
        verify(gateway, never()).refund(
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString()
        );
        server.verify();
    }

    @Test
    void hostedPartialProposalsCannotDowngradeApprovalOrDenialTiers() {
        assertRejectedHostedAmount(47_500, 3_500, "CAPTURE-475");
        assertRejectedHostedAmount(60_000, 3_500, "CAPTURE-600");
    }

    @Test
    void exactFullBalanceHostedProposalsKeepExistingPolicyThresholds() {
        HostedHarness small = hostedHarness(evidence("CAPTURE-35", 3_500),
                3_500);
        when(small.gateway().refund(
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString()
        )).thenReturn(tools.jackson.databind.json.JsonMapper.builder().build()
                .createObjectNode().put("id", "REFUND-35")
                .put("status", "COMPLETED"));
        ResolutionCaseResponse smallResponse = small.resolution().resolve(
                request("hosted-full-35", "CAPTURE-35"),
                "support-agent"
        );
        assertEquals("COMPLETED", smallResponse.status());
        small.server().verify();

        HostedHarness medium = hostedHarness(
                evidence("CAPTURE-475", 47_500), 47_500
        );
        ResolutionCaseResponse mediumResponse = medium.resolution().resolve(
                request("hosted-full-475", "CAPTURE-475"),
                "support-agent"
        );
        assertEquals("PENDING_APPROVAL", mediumResponse.status());
        assertEquals(RefundState.PENDING_APPROVAL,
                mediumResponse.execution().state());
        verify(medium.gateway(), never()).refund(
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString()
        );
        medium.server().verify();

        HostedHarness large = hostedHarness(
                evidence("CAPTURE-600", 60_000), 60_000
        );
        ResolutionCaseResponse largeResponse = large.resolution().resolve(
                request("hosted-full-600", "CAPTURE-600"),
                "support-agent"
        );
        assertEquals("DENIED", largeResponse.status());
        assertEquals(RefundState.DENIED, largeResponse.execution().state());
        verify(large.gateway(), never()).refund(
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString()
        );
        large.server().verify();
    }

    @Test
    void validatesTimeoutSettingsAndEnforcesReadTimeout() throws Exception {
        assertThrows(
                IllegalArgumentException.class,
                () -> new HostedPaymentResolutionAgent(
                        RestClient.builder(), ENDPOINT, "secret", "model",
                        0, 100
                )
        );
        try (ServerSocket server = new ServerSocket(0);
             var executor = Executors.newSingleThreadExecutor()) {
            executor.submit(() -> stallResponse(server));
            HostedPaymentResolutionAgent agent =
                    new HostedPaymentResolutionAgent(
                            RestClient.builder(),
                            "http://127.0.0.1:" + server.getLocalPort(),
                            "server-secret",
                            "test-model",
                            500,
                            100
                    );
            long started = System.nanoTime();
            assertThrows(RuntimeException.class, () -> agent.propose(
                    "Customer reports a duplicate transaction", EVIDENCE
            ));
            long elapsedMs = TimeUnit.NANOSECONDS.toMillis(
                    System.nanoTime() - started
            );
            assertTrue(elapsedMs < 2_000,
                    "configured read timeout should bound provider wait");
        }
    }

    private static void assertRejected(String modelOutput) {
        RestClient.Builder builder = RestClient.builder().baseUrl(ENDPOINT);
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder)
                .build();
        HostedPaymentResolutionAgent agent = new HostedPaymentResolutionAgent(
                builder.build(), "server-secret", "test-model"
        );
        server.expect(requestTo(ENDPOINT + "/v1/chat/completions"))
                .andRespond(withSuccess(
                        envelope(modelOutput), MediaType.APPLICATION_JSON
                ));
        assertThrows(RuntimeException.class, () -> agent.propose(
                "Customer reports a duplicate transaction", EVIDENCE
        ));
        server.verify();
    }

    private static void assertRejectedHostedAmount(
            long trustedAmount,
            long proposedAmount,
            String captureId
    ) {
        HostedHarness harness = hostedHarness(
                evidence(captureId, trustedAmount), proposedAmount
        );
        ResolutionCaseResponse response = harness.resolution().resolve(
                request("hosted-partial-" + captureId, captureId),
                "support-agent"
        );
        assertEquals("PROPOSAL_REJECTED", response.status());
        assertEquals("MODEL_PROPOSAL_UNSAFE", response.decision().reason());
        assertNull(response.execution());
        assertNull(harness.operationStore().findByIdempotencyKey(
                "payguard-resolution:" + response.caseId()
        ));
        verify(harness.gateway(), never()).refund(
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString()
        );
        harness.server().verify();
    }

    private static HostedHarness hostedHarness(
            TransactionEvidence evidence,
            long proposedAmount
    ) {
        RestClient.Builder builder = RestClient.builder().baseUrl(ENDPOINT);
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder)
                .build();
        HostedPaymentResolutionAgent agent = new HostedPaymentResolutionAgent(
                builder.build(), "server-secret", "test-model"
        );
        server.expect(requestTo(ENDPOINT + "/v1/chat/completions"))
                .andExpect(method(POST))
                .andRespond(withSuccess(
                        envelope(VALID_OUTPUT.replace(
                                "\"suggestedAmountCents\":3500",
                                "\"suggestedAmountCents\":"
                                        + proposedAmount
                        )),
                        MediaType.APPLICATION_JSON
                ));
        PayPalRefundGateway gateway = mock(PayPalRefundGateway.class);
        RefundOperationStore operations =
                new RefundOperationStore(java.time.Clock.systemUTC());
        ResolutionService resolution = new ResolutionService(
                captureId -> evidence,
                agent,
                new RefundService(
                        gateway,
                        operations,
                        new DemoBudgetDecision(),
                        event -> {},
                        java.time.Clock.systemUTC()
                ),
                new InMemoryResolutionCaseRepository(),
                java.time.Clock.systemUTC(),
                evidence.mandateId()
        );
        return new HostedHarness(server, resolution, operations, gateway);
    }

    private static TransactionEvidence evidence(
            String captureId,
            long amount
    ) {
        return new TransactionEvidence(
                captureId,
                amount,
                "USD",
                Instant.parse("2026-10-01T00:00:00Z"),
                "payguard-demo-mandate",
                "COMPLETED",
                amount,
                List.of(),
                "fixture-v1-" + captureId,
                Instant.parse("2026-10-10T00:00:00Z")
        );
    }

    private static ResolutionCaseRequest request(String key, String captureId) {
        return new ResolutionCaseRequest(
                "Customer reports a duplicate charge", captureId, key
        );
    }

    private record HostedHarness(
            MockRestServiceServer server,
            ResolutionService resolution,
            RefundOperationStore operationStore,
            PayPalRefundGateway gateway
    ) {}

    private static String envelope(String content) {
        return """
                {"choices":[{"message":{"content":%s}}]}
                """.formatted(
                tools.jackson.databind.json.JsonMapper.builder().build()
                        .writeValueAsString(content)
        );
    }

    private static void stallResponse(ServerSocket server) {
        try (Socket socket = server.accept();
             BufferedInputStream input =
                     new BufferedInputStream(socket.getInputStream())) {
            int matched = 0;
            byte[] endOfHeaders = "\r\n\r\n".getBytes(StandardCharsets.US_ASCII);
            while (matched < endOfHeaders.length) {
                int next = input.read();
                if (next < 0) {
                    return;
                }
                matched = next == endOfHeaders[matched]
                        ? matched + 1 : next == endOfHeaders[0] ? 1 : 0;
            }
            Thread.sleep(700);
            socket.getOutputStream().write((
                    "HTTP/1.1 200 OK\r\n"
                            + "Content-Type: application/json\r\n"
                            + "Content-Length: 2\r\n"
                            + "Connection: close\r\n\r\n{}"
            ).getBytes(StandardCharsets.US_ASCII));
        } catch (IOException expectedAfterClientTimeout) {
            // The client can close the socket as soon as its read timeout expires.
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
    }
}
