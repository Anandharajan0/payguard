package com.anandharajan.payguard.policy;

import com.anandharajan.payguard.paypal.PayPalClient;
import com.anandharajan.payguard.paypal.PayPalRefundOutcome;
import com.anandharajan.payguard.paypal.PayPalRefundOutcomeMapper;
import com.anandharajan.payguard.audit.AuditEvent;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.time.Clock;
import java.util.ArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class RefundFirewallSpineTest {

    private final PayPalClient payPal = mock(PayPalClient.class);
    private final RefundService service = new RefundService(
            payPal,
            new RefundOperationStore(Clock.systemUTC()),
            new DemoBudgetDecision(),
            event -> {},
            Clock.systemUTC()
    );

    @Test
    void thirtyFiveDollarsIsAutomaticallyApprovedAndExecuted() {
        when(payPal.refund(eq("CAPTURE-35"), eq(3_500L), eq("USD"), anyString()))
                .thenReturn(JsonMapper.builder().build().createObjectNode()
                        .put("id", "REFUND-35")
                        .put("status", "COMPLETED"));

        RefundExecutionResult result = service.refund(
                request("CAPTURE-35", 3_500L),
                "key-capture-35"
        );

        assertEquals(RefundState.EXECUTED, result.state());
        assertEquals("REFUND-35", result.refundId());
        assertNotNull(result.correlationId());
        verify(payPal).refund(eq("CAPTURE-35"), eq(3_500L), eq("USD"), anyString());
    }

    @Test
    void fourHundredSeventyFiveDollarsRequiresApprovalWithoutCallingPayPal() {
        RefundExecutionResult result = service.refund(
                request("CAPTURE-475", 47_500L),
                "key-capture-475"
        );

        assertEquals(RefundState.PENDING_APPROVAL, result.state());
        assertNotNull(result.approvalId());
        assertNull(result.refundId());
        verifyNoInteractions(payPal);
    }

    @Test
    void sixHundredDollarsIsDeniedWithoutCallingPayPal() {
        RefundExecutionResult result = service.refund(
                request("CAPTURE-600", 60_000L),
                "key-capture-600"
        );

        assertEquals(RefundState.DENIED, result.state());
        assertEquals("EXCEEDS_HUMAN_APPROVAL_LIMIT", result.decision().reason());
        verifyNoInteractions(payPal);
    }

    @Test
    void invalidAndFutureRequestsAreDeniedBeforePolicyThresholds() {
        RefundDecision invalid = service.evaluate(new RefundRequest(
                "not valid!",
                3_500L,
                "usd",
                Instant.now().plusSeconds(60),
                AgentContext.authenticatedAgent("test-agent", "test-mandate")
        ));

        assertEquals(RefundState.DENIED, invalid.state());
        assertFalse(invalid.policyTrace().entries().isEmpty());
        assertEquals("CAPTURE_ID_INVALID", invalid.reason());
    }

    @Test
    void inactiveMandateCannotAuthorizeRefund() {
        RefundRequest request = new RefundRequest(
                "CAPTURE-35",
                3_500L,
                "USD",
                Instant.now(),
                new AgentContext(
                        "untrusted-agent",
                        true,
                        new MerchantMandate("merchant-1", MerchantMandateStatus.REVOKED)
                )
        );

        RefundExecutionResult result = service.refund(request, "key-revoked");

        assertEquals(RefundState.DENIED, result.state());
        assertEquals("AGENT_NOT_AUTHORIZED", result.decision().reason());
        verifyNoInteractions(payPal);
    }

    @Test
    void duplicateIdempotencyKeyExecutesPayPalOnlyOnce() {
        when(payPal.refund(eq("CAPTURE-DUP"), eq(3_500L), eq("USD"), anyString()))
                .thenReturn(JsonMapper.builder().build().createObjectNode()
                        .put("id", "REFUND-DUP")
                        .put("status", "COMPLETED"));

        RefundRequest request = request("CAPTURE-DUP", 3_500L);
        RefundExecutionResult first = service.refund(request, "key-duplicate");
        RefundExecutionResult second = service.refund(request, "key-duplicate");

        assertEquals(first.operationId(), second.operationId());
        assertEquals(RefundState.EXECUTED, second.state());
        verify(payPal).refund(eq("CAPTURE-DUP"), eq(3_500L), eq("USD"), eq(first.operationId()));
    }

    @Test
    void reusingIdempotencyKeyForDifferentRequestIsRejected() {
        service.refund(request("CAPTURE-A", 3_500L), "key-conflict");

        assertThrows(
                IdempotencyKeyException.class,
                () -> service.refund(request("CAPTURE-B", 3_500L), "key-conflict")
        );
    }

    @Test
    void separateApproverCanExecutePendingRefundAndSelfApprovalFails() {
        RefundRequest pendingRequest = request("CAPTURE-475", 47_500L);
        RefundExecutionResult pending = service.refund(
                pendingRequest,
                "key-approval"
        );

        assertThrows(
                SelfApprovalException.class,
                () -> service.approve(
                        pending.approvalId(),
                        new ApproverContext("test-agent", "test-mandate", true)
                )
        );
        assertEquals(RefundState.PENDING_APPROVAL,
                service.refund(pendingRequest, "key-approval").state());

        when(payPal.refund(eq("CAPTURE-475"), eq(47_500L), eq("USD"), anyString()))
                .thenReturn(JsonMapper.builder().build().createObjectNode()
                        .put("id", "REFUND-475")
                        .put("status", "COMPLETED"));

        RefundExecutionResult executed = service.approve(
                pending.approvalId(),
                new ApproverContext("test-approver", "test-mandate", true)
        );

        assertEquals(RefundState.EXECUTED, executed.state());
        verify(payPal).refund(
                eq("CAPTURE-475"),
                eq(47_500L),
                eq("USD"),
                eq(pending.operationId())
        );
    }

    @Test
    void crossMandateApprovalIsRejectedBeforePayPal() {
        RefundExecutionResult pending = service.refund(
                request("CAPTURE-SCOPE", 47_500L),
                "key-scope"
        );

        assertThrows(
                ApprovalScopeException.class,
                () -> service.approve(
                        pending.approvalId(),
                        new ApproverContext("other-approver", "other-mandate", true)
                )
        );
        verifyNoInteractions(payPal);
    }

    @Test
    void concurrentApprovalAttemptsCanExecuteAtMostOnce() throws Exception {
        CountDownLatch providerStarted = new CountDownLatch(1);
        CountDownLatch releaseProvider = new CountDownLatch(1);
        when(payPal.refund(eq("CAPTURE-CONCURRENT"), eq(47_500L), eq("USD"), anyString()))
                .thenAnswer(invocation -> {
                    providerStarted.countDown();
                    releaseProvider.await();
                    return JsonMapper.builder().build().createObjectNode()
                            .put("id", "REFUND-CONCURRENT")
                            .put("status", "COMPLETED");
                });

        RefundExecutionResult pending = service.refund(
                request("CAPTURE-CONCURRENT", 47_500L),
                "key-concurrent"
        );
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<RefundExecutionResult> first = executor.submit(
                    () -> service.approve(
                            pending.approvalId(),
                            new ApproverContext("approver-1", "test-mandate", true)
                    )
            );
            providerStarted.await();
            Future<RefundExecutionResult> second = executor.submit(
                    () -> service.approve(
                            pending.approvalId(),
                            new ApproverContext("approver-2", "test-mandate", true)
                    )
            );
            releaseProvider.countDown();

            assertEquals(RefundState.EXECUTED, first.get().state());
            assertEquals(RefundState.IN_FLIGHT, second.get().state());
            verify(payPal).refund(
                    eq("CAPTURE-CONCURRENT"),
                    eq(47_500L),
                    eq("USD"),
                    eq(pending.operationId())
            );
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void providerExceptionIsVisibleAndAuditedAsReconciliationRequired() {
        ArrayList<AuditEvent> events = new ArrayList<>();
        RefundService failureService = new RefundService(
                payPal,
                new RefundOperationStore(Clock.systemUTC()),
                new DemoBudgetDecision(),
                events::add,
                Clock.systemUTC()
        );
        when(payPal.refund(eq("CAPTURE-FAIL"), eq(3_500L), eq("USD"), anyString()))
                .thenThrow(new RuntimeException("provider timeout"));

        RefundExecutionResult result = failureService.refund(
                request("CAPTURE-FAIL", 3_500L),
                "key-failure"
        );

        assertEquals(RefundState.RECONCILIATION_REQUIRED, result.state());
        assertEquals("PAYPAL_OUTCOME_AMBIGUOUS", result.error());
        assertEquals(RefundState.RECONCILIATION_REQUIRED,
                events.getLast().state());
        assertEquals(result.operationId(), events.getLast().operationId());
    }

    @Test
    void pendingProviderOutcomeCannotCompleteOperationAsExecuted() {
        ArrayList<AuditEvent> events = new ArrayList<>();
        RefundService pendingService = new RefundService(
                payPal,
                new RefundOperationStore(Clock.systemUTC()),
                new DemoBudgetDecision(),
                events::add,
                Clock.systemUTC()
        );
        when(payPal.refund(eq("CAPTURE-PENDING"), eq(3_500L), eq("USD"),
                anyString()))
                .thenReturn(JsonMapper.builder().build().createObjectNode()
                        .put("id", "REFUND-PENDING")
                        .put("status", "PENDING"));

        RefundExecutionResult result = pendingService.refund(
                request("CAPTURE-PENDING", 3_500L),
                "key-pending"
        );

        assertEquals(
                RefundState.RECONCILIATION_REQUIRED,
                result.state()
        );
        assertNotEquals(RefundState.EXECUTED, result.state());
        assertEquals("REFUND-PENDING", result.refundId());
        assertEquals("PENDING", events.getLast().paypalStatus());
        assertEquals(
                RefundState.RECONCILIATION_REQUIRED,
                events.getLast().state()
        );
    }

    @Test
    void operationCannotCompleteBeforePayPalExecutionBegins() {
        RefundOperation operation = new RefundOperation(
                "operation-not-in-flight",
                "key-not-in-flight",
                "fingerprint",
                request("CAPTURE-NOT-IN-FLIGHT", 3_500L),
                new RefundDecision(
                        RefundDecision.Status.APPROVED,
                        "WITHIN_AUTO_APPROVAL_LIMIT"
                ),
                Clock.systemUTC()
        );

        PayPalRefundOutcome outcome = new PayPalRefundOutcomeMapper().map(
                JsonMapper.builder().build().createObjectNode()
                        .put("id", "REFUND-NOT-IN-FLIGHT")
                        .put("status", "COMPLETED")
        );

        assertThrows(
                IllegalStateException.class,
                () -> operation.complete(outcome)
        );
    }

    private static RefundRequest request(String captureId, long amountCents) {
        return new RefundRequest(
                captureId,
                amountCents,
                "USD",
                Instant.now().minusSeconds(60),
                AgentContext.authenticatedAgent("test-agent", "test-mandate")
        );
    }
}
