package com.anandharajan.payguard.policy;

import com.anandharajan.payguard.paypal.PayPalClient;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.time.Clock;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class RefundServiceTest {

    private final PayPalClient payPalClient = Mockito.mock(PayPalClient.class);
    private final RefundService refundService = new RefundService(
            payPalClient,
            new RefundOperationStore(Clock.systemUTC()),
            new DemoBudgetDecision(),
            event -> {},
            Clock.systemUTC()
    );

    @Test
    void deniedRefundNeverCallsPayPal() {
        RefundRequest request = new RefundRequest(
                "CAPTURE-1",
                null,
                "USD",
                Instant.now(),
                AgentContext.authenticatedAgent("test-agent", "test-mandate")
        );

        RefundExecutionResult result = refundService.refund(request, "key-denied");

        assertEquals(RefundDecision.Status.DENIED, result.decision().status());
        assertNull(result.refundId());

        Mockito.verifyNoInteractions(payPalClient);
    }

    @Test
    void approvedRefundCallsPayPal() {
        RefundRequest request = new RefundRequest(
                "CAPTURE-2",
                3_000L,
                "USD",
                Instant.now(),
                AgentContext.authenticatedAgent("test-agent", "test-mandate")
        );

        Mockito.when(payPalClient.refund(
                Mockito.eq("CAPTURE-2"),
                Mockito.eq(3_000L),
                Mockito.eq("USD"),
                Mockito.anyString()
        )).thenReturn(JsonMapper.builder().build().createObjectNode()
                .put("id", "REFUND-1")
                .put("status", "COMPLETED"));

        RefundExecutionResult result = refundService.refund(request, "key-approved");

        assertEquals(RefundDecision.Status.APPROVED, result.decision().status());
        assertEquals("REFUND-1", result.refundId());
        assertEquals("COMPLETED", result.paypalStatus());

        Mockito.verify(payPalClient).refund(
                Mockito.eq("CAPTURE-2"),
                Mockito.eq(3_000L),
                Mockito.eq("USD"),
                Mockito.anyString()
        );
    }

    @Test
    void defaultServiceFailsClosedWhenBudgetIsUnevaluated() {
        RefundService failClosedService = new RefundService(
                payPalClient,
                new RefundOperationStore(Clock.systemUTC()),
                event -> {},
                Clock.systemUTC()
        );

        RefundExecutionResult result = failClosedService.refund(
                new RefundRequest(
                        "CAPTURE-DEFAULT",
                        3_000L,
                        "USD",
                        Instant.now(),
                        AgentContext.authenticatedAgent(
                                "test-agent",
                                "test-mandate"
                        )
                ),
                "key-default-budget"
        );

        assertEquals(RefundState.DENIED, result.state());
        assertEquals("DAILY_BUDGET_NOT_EVALUATED",
                result.decision().reason());
        Mockito.verifyNoInteractions(payPalClient);
    }
}
