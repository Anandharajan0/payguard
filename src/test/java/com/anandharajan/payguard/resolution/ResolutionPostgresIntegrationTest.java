package com.anandharajan.payguard.resolution;

import com.anandharajan.payguard.audit.JdbcAuditSink;
import com.anandharajan.payguard.paypal.PayPalRefundGateway;
import com.anandharajan.payguard.policy.ApproverContext;
import com.anandharajan.payguard.policy.CaptureRefundConflictException;
import com.anandharajan.payguard.policy.DatabaseBudgetDecision;
import com.anandharajan.payguard.policy.RefundExecutionResult;
import com.anandharajan.payguard.policy.RefundOperation;
import com.anandharajan.payguard.policy.RefundService;
import com.anandharajan.payguard.policy.RefundState;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import tools.jackson.databind.json.JsonMapper;

import java.time.Clock;
import java.time.Instant;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@EnabledIfEnvironmentVariable(
        named = "PAYGUARD_TEST_POSTGRES_URL",
        matches = ".+"
)
class ResolutionPostgresIntegrationTest {

    private static final Clock CLOCK = Clock.systemUTC();
    private static final String MANDATE = "payguard-demo-mandate";
    private static final String URL = System.getenv(
            "PAYGUARD_TEST_POSTGRES_URL"
    );
    private final DriverManagerDataSource dataSource =
            new DriverManagerDataSource(
                    URL,
                    System.getenv().getOrDefault(
                            "PAYGUARD_TEST_POSTGRES_USER", "anandharajan"
                    ),
                    System.getenv().getOrDefault(
                            "PAYGUARD_TEST_POSTGRES_PASSWORD", ""
                    )
            );
    private final JdbcTemplate jdbc = new JdbcTemplate(dataSource);

    @BeforeEach
    void resetDatabase() {
        Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration")
                .load()
                .migrate();
        jdbc.update("delete from resolution_cases");
        jdbc.update("delete from audit_events");
        jdbc.update("delete from budget_reservations");
        jdbc.update("delete from refund_operations");
        jdbc.update("delete from budget_days");
    }

    @Test
    void concurrentIdenticalRequestsInvokeModelOnceAndReturnInProgress()
            throws Exception {
        CountDownLatch modelEntered = new CountDownLatch(1);
        CountDownLatch releaseModel = new CountDownLatch(1);
        AtomicInteger modelCalls = new AtomicInteger();
        PaymentResolutionAgent agent = (issue, evidence) -> {
            modelCalls.incrementAndGet();
            modelEntered.countDown();
            await(releaseModel);
            return proposal(evidence, "PROPOSE_REFUND",
                    evidence.remainingRefundableCents());
        };
        PayPalRefundGateway gateway = successfulGateway();
        ResolutionService firstService = service(agent, gateway);
        ResolutionService secondService = service(agent, gateway);
        ResolutionCaseRequest request = request("concurrent-identical-key",
                "CAPTURE-35", "Customer reports a duplicate charge");

        try (var executor = Executors.newSingleThreadExecutor()) {
            var first = executor.submit(
                    () -> firstService.resolve(request, "support-agent")
            );
            assertTrue(modelEntered.await(5, TimeUnit.SECONDS));

            ResolutionCaseResponse concurrent =
                    secondService.resolve(request, "support-agent");
            assertEquals("PROCESSING", concurrent.status());
            assertEquals("RESOLUTION_IN_PROGRESS",
                    concurrent.decision().reason());

            releaseModel.countDown();
            ResolutionCaseResponse completed = first.get(10, TimeUnit.SECONDS);
            assertEquals("COMPLETED", completed.status());
            assertEquals(1, modelCalls.get());
            assertEquals(1L, operationCount(request.idempotencyKey()));
        } finally {
            releaseModel.countDown();
        }
    }

    @Test
    void concurrentConflictingFingerprintIsRejectedWithoutSecondModelCall()
            throws Exception {
        CountDownLatch modelEntered = new CountDownLatch(1);
        CountDownLatch releaseModel = new CountDownLatch(1);
        AtomicInteger modelCalls = new AtomicInteger();
        PaymentResolutionAgent agent = (issue, evidence) -> {
            modelCalls.incrementAndGet();
            modelEntered.countDown();
            await(releaseModel);
            return proposal(evidence, "PROPOSE_REFUND",
                    evidence.remainingRefundableCents());
        };
        ResolutionService service = service(agent, successfulGateway());
        ResolutionCaseRequest firstRequest = request(
                "concurrent-conflicting-key",
                "CAPTURE-35",
                "Customer reports a duplicate charge"
        );

        try (var executor = Executors.newSingleThreadExecutor()) {
            var first = executor.submit(
                    () -> service.resolve(firstRequest, "support-agent")
            );
            assertTrue(modelEntered.await(5, TimeUnit.SECONDS));
            assertThrows(
                    IllegalArgumentException.class,
                    () -> service.resolve(
                            request(
                                    firstRequest.idempotencyKey(),
                                    "CAPTURE-475",
                                    "Different customer issue"
                            ),
                            "support-agent"
                    )
            );
            releaseModel.countDown();
            first.get(10, TimeUnit.SECONDS);
        } finally {
            releaseModel.countDown();
        }
        assertEquals(1, modelCalls.get());
        assertEquals(1L, jdbc.queryForObject(
                "select count(*) from resolution_cases", Long.class
        ));
    }

    @Test
    void interruptedProcessingCanBeReclaimedAndModelFailureIsTerminal() {
        JdbcResolutionCaseRepository repository =
                new JdbcResolutionCaseRepository(dataSource);
        TransactionEvidence evidence =
                new FixtureTransactionEvidenceProvider(CLOCK).find("CAPTURE-35");
        ResolutionCaseClaim claim = repository.claim(
                MANDATE,
                "interrupted-processing-key",
                fingerprint(
                        "Customer reports a duplicate charge", "CAPTURE-35"
                ),
                "Customer reports a duplicate charge",
                evidence
        );
        assertThrows(
                IllegalStateException.class,
                () -> repository.processProposal(
                        claim.resolutionCase().caseId(),
                        claim.claimToken(),
                        current -> {
                            throw new IllegalStateException("simulated crash");
                        }
                )
        );
        assertEquals(ResolutionCaseStatus.PROCESSING,
                repository.findByKey(MANDATE,
                        "interrupted-processing-key").status());
        jdbc.update(
                """
                update resolution_cases
                   set processing_lease_expires_at = now() - interval '1 second'
                 where idempotency_key = 'interrupted-processing-key'
                """
        );

        AtomicInteger modelCalls = new AtomicInteger();
        PaymentResolutionAgent recoveringAgent = (issue, trustedEvidence) -> {
            modelCalls.incrementAndGet();
            return proposal(trustedEvidence, "PROPOSE_REFUND",
                    trustedEvidence.remainingRefundableCents());
        };
        ResolutionService recovery = service(recoveringAgent, successfulGateway());
        ResolutionCaseResponse recovered = recovery.resolve(
                request("interrupted-processing-key", "CAPTURE-35",
                        "Customer reports a duplicate charge"),
                "support-agent"
        );
        assertEquals("COMPLETED", recovered.status());
        assertEquals(1, modelCalls.get());

        AtomicInteger failedCalls = new AtomicInteger();
        PaymentResolutionAgent failingAgent = (issue, trustedEvidence) -> {
            failedCalls.incrementAndGet();
            throw new IllegalStateException("provider unavailable");
        };
        ResolutionService failedService = service(failingAgent, successfulGateway());
        ResolutionCaseRequest failedRequest = request(
                "model-failure-terminal-key",
                "CAPTURE-475",
                "Customer reports a duplicate charge"
        );
        assertEquals("MODEL_FAILED",
                failedService.resolve(failedRequest, "support-agent").status());
        assertEquals("MODEL_FAILED",
                failedService.resolve(failedRequest, "support-agent").status());
        assertEquals(1, failedCalls.get());
    }

    @Test
    void retryAfterPersistedProposalDoesNotInvokeModelAgain() {
        JdbcResolutionCaseRepository repository =
                new JdbcResolutionCaseRepository(dataSource);
        TransactionEvidence evidence =
                new FixtureTransactionEvidenceProvider(CLOCK).find("CAPTURE-35");
        ResolutionCaseClaim claim = repository.claim(
                MANDATE,
                "proposal-persisted-retry-key",
                fingerprint(
                        "Customer reports a duplicate charge", "CAPTURE-35"
                ),
                "Customer reports a duplicate charge",
                evidence
        );
        ResolutionProposal durableProposal = proposal(
                evidence, "PROPOSE_REFUND", evidence.remainingRefundableCents()
        );
        ResolutionCase persisted = repository.processProposal(
                claim.resolutionCase().caseId(),
                claim.claimToken(),
                current -> new ResolutionProposalResult(
                        durableProposal,
                        ResolutionCaseStatus.PROPOSAL_READY,
                        null
                )
        );
        assertEquals(durableProposal, persisted.proposal());

        PaymentResolutionAgent model = mock(PaymentResolutionAgent.class);
        PayPalRefundGateway gateway = successfulGateway();
        ResolutionService recovery = service(model, gateway);
        ResolutionCaseResponse recovered = recovery.resolve(
                request(
                        "proposal-persisted-retry-key",
                        "CAPTURE-35",
                        "Customer reports a duplicate charge"
                ),
                "support-agent"
        );

        assertEquals(durableProposal, recovered.proposal());
        assertEquals("COMPLETED", recovered.status());
        verify(model, never()).propose(
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.any()
        );
        verify(gateway, times(1)).refund(
                eq("CAPTURE-35"), eq(3_500L), eq("USD"), anyString()
        );
    }

    @Test
    void replayRecoversExecutedOperationAfterLinkageInterruption() {
        PaymentResolutionAgent agent = mock(PaymentResolutionAgent.class);
        AtomicInteger linkAttempts = new AtomicInteger();
        ResolutionCaseRepository failingLinkRepository =
                new LinkFailureOnceRepository(
                        new JdbcResolutionCaseRepository(dataSource),
                        linkAttempts
                );
        PayPalRefundGateway gateway = successfulGateway();
        RefundService refundService = refundService(gateway);
        ResolutionService interruptedService = new ResolutionService(
                new FixtureTransactionEvidenceProvider(CLOCK),
                agent,
                refundService,
                failingLinkRepository,
                CLOCK,
                MANDATE
        );
        ResolutionCaseRequest request = request(
                "linkage-interruption-key",
                "CAPTURE-35",
                "Customer reports a duplicate charge"
        );
        when(agent.propose(eq(request.customerIssue()), org.mockito.ArgumentMatchers.any()))
                .thenAnswer(invocation -> {
                    TransactionEvidence evidence = invocation.getArgument(1);
                    return proposal(evidence, "PROPOSE_REFUND",
                            evidence.remainingRefundableCents());
                });

        assertThrows(
                IllegalStateException.class,
                () -> interruptedService.resolve(request, "support-agent")
        );
        assertEquals(1L, jdbc.queryForObject(
                "select count(*) from refund_operations", Long.class
        ));
        String durableOperationId = jdbc.queryForObject(
                """
                select operation_id::text from refund_operations
                 where idempotency_key = ?
                """,
                String.class,
                "payguard-resolution:" + jdbc.queryForObject(
                        "select case_id from resolution_cases where idempotency_key = ?",
                        java.util.UUID.class,
                        request.idempotencyKey()
                )
        );
        String durableCorrelationId = jdbc.queryForObject(
                "select correlation_id::text from refund_operations where operation_id = ?",
                String.class,
                java.util.UUID.fromString(durableOperationId)
        );
        String durableProviderRequestId = jdbc.queryForObject(
                "select provider_request_id from refund_operations where operation_id = ?",
                String.class,
                java.util.UUID.fromString(durableOperationId)
        );
        ResolutionService replayService = service(agent, gateway);
        ResolutionCaseResponse recovered =
                replayService.resolve(request, "support-agent");

        assertEquals("COMPLETED", recovered.status());
        assertEquals("PROPOSE_REFUND", recovered.proposal().action());
        assertEquals(durableOperationId,
                recovered.execution().operationId());
        assertEquals(durableCorrelationId,
                recovered.execution().correlationId());
        assertEquals(durableProviderRequestId,
                recovered.execution().operationId());
        assertEquals(1, linkAttempts.get());
        verify(agent, times(1)).propose(
                eq(request.customerIssue()),
                org.mockito.ArgumentMatchers.any()
        );
        verify(gateway, times(1)).refund(
                eq("CAPTURE-35"), eq(3_500L), eq("USD"), anyString()
        );
    }

    @Test
    void pendingFourHundredSeventyFiveUpdatesFromAuthoritativeOperation() {
        PayPalRefundGateway gateway = successfulGateway();
        ResolutionService resolution = service(
                new DeterministicPaymentResolutionAgent(), gateway
        );
        ResolutionCaseRequest request = request(
                "approval-state-sync-key",
                "CAPTURE-475",
                "Customer says duplicate transaction and requests refund"
        );
        ResolutionCaseResponse pending = resolution.resolve(
                request, "support-agent"
        );
        assertEquals("PENDING_APPROVAL", pending.status());
        assertEquals(RefundState.PENDING_APPROVAL, pending.execution().state());
        assertEquals(0, invocationCount(gateway));

        RefundService refund = refundService(gateway);
        RefundExecutionResult approved = refund.approve(
                pending.execution().approvalId(),
                new ApproverContext("human-reviewer", MANDATE, true)
        );
        assertEquals(RefundState.EXECUTED, approved.state());

        ResolutionCaseResponse replay = resolution.resolve(
                request, "support-agent"
        );
        assertEquals("COMPLETED", replay.status());
        assertEquals(RefundState.EXECUTED, replay.execution().state());
        assertEquals(approved.operationId(), replay.execution().operationId());
        verify(gateway, times(1)).refund(
                eq("CAPTURE-475"), eq(47_500L), eq("USD"), anyString()
        );
    }

    @Test
    void differentResolutionKeyCannotRefundCompletedCaptureAgain() {
        PayPalRefundGateway gateway = successfulGateway();
        ResolutionService resolution = service(
                new DeterministicPaymentResolutionAgent(), gateway
        );

        ResolutionCaseResponse first = resolution.resolve(
                request("capture-completed-first", "CAPTURE-35",
                        "Customer reports a duplicate charge"),
                "support-agent"
        );
        assertEquals("COMPLETED", first.status());

        assertThrows(
                CaptureRefundConflictException.class,
                () -> resolution.resolve(
                        request("capture-completed-second", "CAPTURE-35",
                                "Another duplicate charge requests a refund"),
                        "support-agent"
                )
        );
        assertEquals(1L, jdbc.queryForObject(
                "select count(*) from refund_operations where capture_id = ?",
                Long.class,
                "CAPTURE-35"
        ));
        assertEquals(1, invocationCount(gateway));
    }

    @Test
    void concurrentDifferentKeysCannotBypassCaptureGuard() throws Exception {
        CountDownLatch modelsEntered = new CountDownLatch(2);
        CountDownLatch releaseModels = new CountDownLatch(1);
        AtomicInteger modelCalls = new AtomicInteger();
        PaymentResolutionAgent agent = (issue, evidence) -> {
            modelCalls.incrementAndGet();
            modelsEntered.countDown();
            await(releaseModels);
            return proposal(evidence, "PROPOSE_REFUND",
                    evidence.remainingRefundableCents());
        };
        PayPalRefundGateway gateway = successfulGateway();
        ResolutionService firstService = service(agent, gateway);
        ResolutionService secondService = service(agent, gateway);

        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> resolveOrCaptureConflict(
                    firstService,
                    request("capture-race-first", "CAPTURE-35",
                            "Customer reports a duplicate charge")
            ));
            var second = executor.submit(() -> resolveOrCaptureConflict(
                    secondService,
                    request("capture-race-second", "CAPTURE-35",
                            "Customer reports another duplicate charge")
            ));

            assertTrue(modelsEntered.await(5, TimeUnit.SECONDS));
            releaseModels.countDown();
            List<Object> results = List.of(
                    first.get(10, TimeUnit.SECONDS),
                    second.get(10, TimeUnit.SECONDS)
            );
            assertEquals(1, results.stream()
                    .filter(ResolutionCaseResponse.class::isInstance).count());
            assertEquals(1, results.stream()
                    .filter(CaptureRefundConflictException.class::isInstance)
                    .count());
        } finally {
            releaseModels.countDown();
        }

        assertEquals(2, modelCalls.get());
        assertEquals(1L, jdbc.queryForObject(
                "select count(*) from refund_operations where capture_id = ?",
                Long.class,
                "CAPTURE-35"
        ));
        assertEquals(1, invocationCount(gateway));
    }

    @Test
    void definiteAndAmbiguousFailuresRemainDifferentAndKeepBudgetCorrect() {
        PayPalRefundGateway gateway = mock(PayPalRefundGateway.class);
        when(gateway.refund(eq("CAPTURE-35"), eq(3_500L), eq("USD"), anyString()))
                .thenReturn(JsonMapper.builder().build().createObjectNode()
                        .put("id", "REFUND-FAILED")
                        .put("status", "FAILED"))
                .thenReturn(null);
        ResolutionService resolution = service(
                new DeterministicPaymentResolutionAgent(), gateway
        );

        ResolutionCaseResponse failed = resolution.resolve(
                request("definite-failure-key", "CAPTURE-35",
                        "Customer requests a refund for duplicate charge"),
                "support-agent"
        );
        ResolutionCaseResponse ambiguous = resolution.resolve(
                request("ambiguous-outcome-key", "CAPTURE-35",
                        "Customer requests refund for another duplicate charge"),
                "support-agent"
        );
        assertEquals("FAILED", failed.status());
        assertEquals(RefundState.FAILED, failed.execution().state());
        assertEquals("RECONCILIATION_REQUIRED", ambiguous.status());
        assertEquals(RefundState.RECONCILIATION_REQUIRED,
                ambiguous.execution().state());
        assertEquals(1L, jdbc.queryForObject(
                """
                select count(*) from budget_reservations
                 where status = 'RELEASED'
                """,
                Long.class
        ));
        assertEquals(1L, jdbc.queryForObject(
                """
                select count(*) from budget_reservations
                 where status = 'RESERVED'
                """,
                Long.class
        ));
        assertEquals(1L, jdbc.queryForObject(
                """
                select count(*) from refund_operations
                 where state = 'FAILED'
                """,
                Long.class
        ));
        assertEquals(1L, jdbc.queryForObject(
                """
                select count(*) from refund_operations
                 where state = 'RECONCILIATION_REQUIRED'
                """,
                Long.class
        ));
        assertEquals(1L, jdbc.queryForObject(
                """
                select count(*) from resolution_cases rc
                join refund_operations ro on ro.operation_id = rc.operation_id
                where ro.state = 'FAILED'
                """,
                Long.class
        ));
        assertEquals(1L, jdbc.queryForObject(
                """
                select count(*) from resolution_cases rc
                join refund_operations ro on ro.operation_id = rc.operation_id
                where ro.state = 'RECONCILIATION_REQUIRED'
                """,
                Long.class
        ));
    }

    @Test
    void replayReturnsOriginalDurableProposalAndIdentifiers() {
        AtomicInteger modelCalls = new AtomicInteger();
        PaymentResolutionAgent agent = (issue, evidence) -> {
            modelCalls.incrementAndGet();
            return proposal(evidence, "PROPOSE_REFUND",
                    evidence.remainingRefundableCents());
        };
        PayPalRefundGateway gateway = successfulGateway();
        ResolutionService resolution = service(agent, gateway);
        ResolutionCaseRequest request = request(
                "completed-replay-original-key",
                "CAPTURE-35",
                "Customer reports a duplicate transaction"
        );

        ResolutionCaseResponse first =
                resolution.resolve(request, "support-agent");
        ResolutionCaseResponse replay =
                resolution.resolve(request, "support-agent");
        assertEquals(first.caseId(), replay.caseId());
        assertEquals(first.proposal(), replay.proposal());
        assertEquals(first.evidence(), replay.evidence());
        assertEquals(first.execution().operationId(),
                replay.execution().operationId());
        assertEquals(first.execution().correlationId(),
                replay.execution().correlationId());
        assertEquals(first.execution().refundId(),
                replay.execution().refundId());
        assertEquals(1, modelCalls.get());
        verify(gateway, times(1)).refund(
                eq("CAPTURE-35"), eq(3_500L), eq("USD"), anyString()
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> resolution.resolve(
                        request(request.idempotencyKey(), "CAPTURE-475",
                                "Conflicting request"),
                        "support-agent"
                )
        );
    }

    private ResolutionService service(
            PaymentResolutionAgent agent,
            PayPalRefundGateway gateway
    ) {
        return new ResolutionService(
                new FixtureTransactionEvidenceProvider(CLOCK),
                agent,
                refundService(gateway),
                new JdbcResolutionCaseRepository(dataSource),
                CLOCK,
                MANDATE
        );
    }

    private RefundService refundService(PayPalRefundGateway gateway) {
        return new RefundService(
                gateway,
                new com.anandharajan.payguard.policy.JdbcRefundOperationRepository(
                        dataSource, CLOCK
                ),
                new DatabaseBudgetDecision(),
                new JdbcAuditSink(dataSource),
                CLOCK,
                new DataSourceTransactionManager(dataSource)
        );
    }

    private PayPalRefundGateway successfulGateway() {
        PayPalRefundGateway gateway = mock(PayPalRefundGateway.class);
        when(gateway.refund(anyString(), org.mockito.ArgumentMatchers.anyLong(),
                anyString(), anyString()))
                .thenReturn(JsonMapper.builder().build().createObjectNode()
                        .put("id", "REFUND-OK")
                        .put("status", "COMPLETED"));
        return gateway;
    }

    private static int invocationCount(PayPalRefundGateway gateway) {
        return (int) org.mockito.Mockito.mockingDetails(gateway)
                .getInvocations().stream()
                .filter(invocation -> invocation.getMethod().getName()
                        .equals("refund"))
                .count();
    }

    private long operationCount(String idempotencyKey) {
        return jdbc.queryForObject(
                "select count(*) from refund_operations where idempotency_key = ?",
                Long.class,
                "payguard-resolution:" + jdbc.queryForObject(
                        """
                        select case_id from resolution_cases
                         where idempotency_key = ?
                        """,
                        java.util.UUID.class,
                        idempotencyKey
                )
        );
    }

    private static ResolutionCaseRequest request(
            String key,
            String captureId,
            String issue
    ) {
        return new ResolutionCaseRequest(issue, captureId, key);
    }

    private static ResolutionProposal proposal(
            TransactionEvidence evidence,
            String action,
            long cents
    ) {
        return new ResolutionProposal(
                action,
                cents,
                evidence.captureId(),
                0.95,
                "Supported by trusted transaction evidence",
                List.of("capture and remaining balance were provided by fixture"),
                List.of(),
                "postgres-test-agent"
        );
    }

    private static String fingerprint(String issue, String captureId) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(
                            (captureId + "\u0000" + issue)
                                    .getBytes(StandardCharsets.UTF_8)
                    )
            );
        } catch (java.security.NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Timed out waiting for test latch");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Test worker was interrupted",
                    exception);
        }
    }

    private static Object resolveOrCaptureConflict(
            ResolutionService service,
            ResolutionCaseRequest request
    ) {
        try {
            return service.resolve(request, "support-agent");
        } catch (CaptureRefundConflictException exception) {
            return exception;
        }
    }

    private static final class LinkFailureOnceRepository
            implements ResolutionCaseRepository {

        private final ResolutionCaseRepository delegate;
        private final AtomicInteger attempts;

        private LinkFailureOnceRepository(
                ResolutionCaseRepository delegate,
                AtomicInteger attempts
        ) {
            this.delegate = delegate;
            this.attempts = attempts;
        }

        @Override
        public ResolutionCase findByKey(String mandateId, String idempotencyKey) {
            return delegate.findByKey(mandateId, idempotencyKey);
        }

        @Override
        public ResolutionCaseClaim claim(
                String mandateId,
                String idempotencyKey,
                String requestFingerprint,
                String customerIssue,
                TransactionEvidence evidence
        ) {
            return delegate.claim(mandateId, idempotencyKey, requestFingerprint,
                    customerIssue, evidence);
        }

        @Override
        public ResolutionCase processProposal(
                String caseId,
                String claimToken,
                java.util.function.Function<ResolutionCase,
                        ResolutionProposalResult> processor
        ) {
            return delegate.processProposal(caseId, claimToken, processor);
        }

        @Override
        public ResolutionCase linkOperation(String caseId, String operationId) {
            if (attempts.getAndIncrement() == 0) {
                throw new IllegalStateException(
                        "Simulated interruption before case linkage"
                );
            }
            return delegate.linkOperation(caseId, operationId);
        }
    }
}
