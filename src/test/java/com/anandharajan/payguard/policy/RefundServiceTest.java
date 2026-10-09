package com.anandharajan.payguard.policy;

import com.anandharajan.payguard.paypal.PayPalClient;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.time.Clock;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

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

    @Test
    void persistenceFailureAfterPayPalSuccessIsNotProviderAmbiguity() {
        RefundOperationStore delegate = new RefundOperationStore(
                Clock.systemUTC()
        );
        FailingCompletionRepository repository =
                new FailingCompletionRepository(delegate);
        RefundService service = new RefundService(
                payPalClient,
                repository,
                new DemoBudgetDecision(),
                event -> {},
                Clock.systemUTC()
        );
        RefundRequest request = new RefundRequest(
                "CAPTURE-PERSISTENCE-FAILURE",
                3_000L,
                "USD",
                Instant.now(),
                AgentContext.authenticatedAgent(
                        "test-agent",
                        "test-mandate"
                )
        );
        Mockito.when(payPalClient.refund(
                Mockito.eq("CAPTURE-PERSISTENCE-FAILURE"),
                Mockito.eq(3_000L),
                Mockito.eq("USD"),
                Mockito.anyString()
        )).thenReturn(JsonMapper.builder().build().createObjectNode()
                .put("id", "REFUND-PERSISTENCE")
                .put("status", "COMPLETED"));

        IllegalStateException failure = assertThrows(
                IllegalStateException.class,
                () -> service.refund(request, "key-persistence-failure")
        );

        assertEquals("database unavailable", failure.getMessage());
        assertEquals(RefundState.IN_FLIGHT, repository.operation.state());
        assertNull(repository.operation.result().refundId());
        Mockito.verify(payPalClient).refund(
                Mockito.eq("CAPTURE-PERSISTENCE-FAILURE"),
                Mockito.eq(3_000L),
                Mockito.eq("USD"),
                Mockito.anyString()
        );
    }

    private static final class FailingCompletionRepository
            implements RefundOperationRepository {

        private final RefundOperationStore delegate;
        private RefundOperation operation;

        private FailingCompletionRepository(RefundOperationStore delegate) {
            this.delegate = delegate;
        }

        @Override
        public RefundOperation getOrCreate(
                String idempotencyKey,
                RefundRequest request,
                RefundDecision decision
        ) {
            operation = delegate.getOrCreate(idempotencyKey, request, decision);
            return operation;
        }

        @Override
        public RefundOperation findByApprovalId(String approvalId) {
            return delegate.findByApprovalId(approvalId);
        }

        @Override
        public RefundOperation findByIdempotencyKey(String idempotencyKey) {
            return delegate.findByIdempotencyKey(idempotencyKey);
        }

        @Override
        public boolean beginAutomaticExecution(String operationId) {
            return delegate.beginAutomaticExecution(operationId);
        }

        @Override
        public boolean approve(
                String approvalId,
                ApproverContext approver
        ) {
            return delegate.approve(approvalId, approver);
        }

        @Override
        public RefundOperation complete(
                String operationId,
                com.anandharajan.payguard.paypal.PayPalRefundOutcome outcome
        ) {
            throw new IllegalStateException("database unavailable");
        }

        @Override
        public int recoverStaleOperations(Instant now) {
            return delegate.recoverStaleOperations(now);
        }
    }
}
