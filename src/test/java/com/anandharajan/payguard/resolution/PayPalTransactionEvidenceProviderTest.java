package com.anandharajan.payguard.resolution;

import com.anandharajan.payguard.paypal.PayPalClient;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verifyNoInteractions;

class PayPalTransactionEvidenceProviderTest {

    private static final CaptureProvenance PROVENANCE =
            new CaptureProvenance(
                    "CAPTURE-LIVE",
                    "payguard-demo-mandate",
                    3_500,
                    "USD",
                    "ORDER-LIVE",
                    Instant.parse("2026-10-17T00:00:00Z"),
                    true
            );

    @Test
    void parsesOnlyAProviderResponseMatchingRegisteredProvenance() {
        var json = JsonMapper.builder().build();
        var response = json.readTree("""
                {
                  "id": "CAPTURE-LIVE",
                  "status": "COMPLETED",
                  "amount": {"value": "35.00", "currency_code": "USD"},
                  "update_time": "2026-10-17T01:02:03Z"
                }
                """);

        TransactionEvidence evidence =
                PayPalTransactionEvidenceProvider.parse(response, PROVENANCE);

        assertEquals("CAPTURE-LIVE", evidence.captureId());
        assertEquals(0, evidence.remainingRefundableCents());
        assertEquals("paypal-capture-unverified-v2", evidence.evidenceVersion());
    }

    @Test
    void rejectsMalformedTypesMismatchesAndUnsupportedStatus() {
        var json = JsonMapper.builder().build();
        List<String> bodies = List.of(
                "{\"id\":123,\"status\":\"COMPLETED\",\"amount\":{\"value\":\"35.00\",\"currency_code\":\"USD\"},\"update_time\":\"2026-10-17T01:02:03Z\"}",
                "{\"id\":\"CAPTURE-LIVE\",\"status\":true,\"amount\":{\"value\":\"35.00\",\"currency_code\":\"USD\"},\"update_time\":\"2026-10-17T01:02:03Z\"}",
                "{\"id\":\"OTHER\",\"status\":\"COMPLETED\",\"amount\":{\"value\":\"35.00\",\"currency_code\":\"USD\"},\"update_time\":\"2026-10-17T01:02:03Z\"}",
                "{\"id\":\"CAPTURE-LIVE\",\"status\":\"PENDING\",\"amount\":{\"value\":\"35.00\",\"currency_code\":\"USD\"},\"update_time\":\"2026-10-17T01:02:03Z\"}",
                "{\"id\":\"CAPTURE-LIVE\",\"status\":\"COMPLETED\",\"amount\":{\"value\":35,\"currency_code\":\"USD\"},\"update_time\":\"2026-10-17T01:02:03Z\"}",
                "{\"id\":\"CAPTURE-LIVE\",\"status\":\"COMPLETED\",\"amount\":{\"value\":\"36.00\",\"currency_code\":\"USD\"},\"update_time\":\"2026-10-17T01:02:03Z\"}",
                "{\"id\":\"CAPTURE-LIVE\",\"status\":\"COMPLETED\",\"amount\":{\"value\":\"35.00\",\"currency_code\":\"EUR\"},\"update_time\":\"2026-10-17T01:02:03Z\"}",
                "{\"id\":\"CAPTURE-LIVE\",\"status\":\"COMPLETED\",\"amount\":{\"value\":\"35.00\",\"currency_code\":\"USD\"},\"update_time\":123}",
                "{malformed"
        );
        for (String body : bodies) {
            try {
                assertNull(
                        PayPalTransactionEvidenceProvider.parse(
                                json.readTree(body), PROVENANCE
                        ),
                        body
                );
            } catch (RuntimeException malformed) {
                assertEquals("{malformed", body);
            }
        }
    }

    @Test
    void liveProviderDoesNotFallBackToFixtureWhenUnregistered() {
        PayPalClient client = mock(PayPalClient.class);
        PayPalTransactionEvidenceProvider provider =
                new PayPalTransactionEvidenceProvider(
                        client,
                        new InMemoryCaptureProvenanceRepository(),
                        java.time.Clock.systemUTC(),
                        "payguard-demo-mandate"
                );

        assertNull(provider.find("CAPTURE-35"));
        verifyNoInteractions(client);
    }

    @Test
    void provenanceFlagCannotAuthorizeARefundableBalance() {
        PayPalClient client = mock(PayPalClient.class);
        var json = JsonMapper.builder().build();
        when(client.capture("CAPTURE-LIVE")).thenReturn(json.readTree("""
                {
                  "id": "CAPTURE-LIVE",
                  "status": "COMPLETED",
                  "amount": {"value": "35.00", "currency_code": "USD"},
                  "update_time": "2026-10-17T01:02:03Z"
                }
                """));
        InMemoryCaptureProvenanceRepository repository =
                new InMemoryCaptureProvenanceRepository();
        repository.register(PROVENANCE);
        PayPalTransactionEvidenceProvider provider =
                new PayPalTransactionEvidenceProvider(
                        client,
                        repository,
                        java.time.Clock.systemUTC(),
                        "payguard-demo-mandate"
                );

        TransactionEvidence evidence = provider.find("CAPTURE-LIVE");

        assertEquals(0, evidence.remainingRefundableCents());
    }

    @Test
    void knownRefundLookupRequiresTypedProviderFields() {
        var json = JsonMapper.builder().build();
        var valid = PayPalTransactionEvidenceProvider.PayPalRefundDetails.parse(
                json.readTree("""
                        {"id":"REFUND-1","status":"COMPLETED",
                         "update_time":"2026-10-17T01:02:03Z"}
                        """)
        );
        assertEquals("REFUND-1", valid.refundId());
        assertEquals("COMPLETED", valid.status());
        assertNull(
                PayPalTransactionEvidenceProvider.PayPalRefundDetails.parse(
                        json.readTree("{\"id\":123,\"status\":\"COMPLETED\"}")
                )
        );
        assertNull(
                PayPalTransactionEvidenceProvider.PayPalRefundDetails.parse(
                        json.readTree("{\"id\":\"REFUND-1\",\"status\":false}")
                )
        );
    }
}
