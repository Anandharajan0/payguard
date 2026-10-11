package com.anandharajan.payguard.policy;

import com.anandharajan.payguard.audit.AuditEvent;
import com.anandharajan.payguard.audit.JdbcAuditSink;
import com.anandharajan.payguard.paypal.PayPalClient;
import com.anandharajan.payguard.paypal.PayPalClientTestSupport;
import com.anandharajan.payguard.paypal.PayPalRefundException;
import com.anandharajan.payguard.paypal.PayPalRefundGateway;
import com.anandharajan.payguard.paypal.PayPalRefundOutcomeMapper;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.ResourceAccessException;

import java.net.SocketTimeoutException;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;

@EnabledIfEnvironmentVariable(
        named = "PAYGUARD_TEST_POSTGRES_URL",
        matches = ".+"
)
class JdbcRefundOperationRepositoryTest {

    private static final String URL = System.getenv(
            "PAYGUARD_TEST_POSTGRES_URL"
    );
    private final Clock clock = Clock.systemUTC();
    private final DriverManagerDataSource dataSource =
            new DriverManagerDataSource(
                    URL,
                    System.getenv().getOrDefault(
                            "PAYGUARD_TEST_POSTGRES_USER",
                            "anandharajan"
                    ),
                    System.getenv().getOrDefault(
                            "PAYGUARD_TEST_POSTGRES_PASSWORD",
                            ""
                    )
            );
    private final JdbcTemplate jdbc = new JdbcTemplate(dataSource);
    private final JdbcRefundOperationRepository repository =
            new JdbcRefundOperationRepository(dataSource, clock);
    private final PayPalRefundOutcomeMapper mapper =
            new PayPalRefundOutcomeMapper();

    @BeforeEach
    void reset() {
        Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration")
                .load()
                .migrate();
        jdbc.update("delete from audit_events");
        jdbc.update("delete from resolution_cases");
        jdbc.update("delete from budget_reservations");
        jdbc.update("delete from refund_operations");
        jdbc.update("delete from budget_days");
    }

    @Test
    void sameKeyReplaysOriginalOperationAndApproval() {
        RefundRequest request = request("CAPTURE-REPLAY", 47_500L);
        RefundDecision decision = approvedDecision(RefundState.PENDING_APPROVAL);

        RefundOperation first = repository.getOrCreate(
                "durable-replay-key", request, decision
        );
        RefundOperation second = repository.getOrCreate(
                "durable-replay-key", request, decision
        );

        assertEquals(first.operationId(), second.operationId());
        assertEquals(first.approvalId(), second.approvalId());
        assertEquals(first.decision().correlationId(),
                second.decision().correlationId());
    }

    @Test
    void conflictingPayloadIsRejectedDurably() {
        repository.getOrCreate(
                "durable-conflict-key",
                request("CAPTURE-A", 3_500L),
                approvedDecision(RefundState.APPROVED)
        );

        assertThrows(
                IdempotencyKeyException.class,
                () -> repository.getOrCreate(
                        "durable-conflict-key",
                        request("CAPTURE-B", 3_500L),
                        approvedDecision(RefundState.APPROVED)
                )
        );
    }

    @Test
    void concurrentSameKeyCreationReturnsOneDurableOperation() throws Exception {
        RefundRequest request = request("CAPTURE-SAME", 3_500L);
        JdbcRefundOperationRepository secondRepository =
                new JdbcRefundOperationRepository(dataSource, clock);
        CountDownLatch start = new CountDownLatch(1);
        var executor = Executors.newFixedThreadPool(2);
        try {
            List<Callable<RefundOperation>> tasks = List.of(
                    () -> {
                        start.await();
                        return repository.getOrCreate(
                                "concurrent-same-key",
                                request,
                                approvedDecision(RefundState.APPROVED)
                        );
                    },
                    () -> {
                        start.await();
                        return secondRepository.getOrCreate(
                                "concurrent-same-key",
                                request,
                                approvedDecision(RefundState.APPROVED)
                        );
                    }
            );
            start.countDown();
            var futures = executor.invokeAll(tasks);
            RefundOperation first = futures.get(0).get();
            RefundOperation second = futures.get(1).get();

            assertEquals(first.operationId(), second.operationId());
            assertEquals(first.decision().correlationId(),
                    second.decision().correlationId());
            assertEquals(1L, jdbc.queryForObject(
                    """
                    select count(*) from refund_operations
                    where mandate_id = 'payguard-demo-mandate'
                      and idempotency_key = 'concurrent-same-key'
                    """,
                    Long.class
            ));
            assertEquals(3_500L, jdbc.queryForObject(
                    "select reserved_cents from budget_days",
                    Long.class
            ));
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void concurrentConflictingKeyCreationHasOneClearConflict() throws Exception {
        JdbcRefundOperationRepository secondRepository =
                new JdbcRefundOperationRepository(dataSource, clock);
        CountDownLatch start = new CountDownLatch(1);
        var executor = Executors.newFixedThreadPool(2);
        try {
            List<Callable<Object>> tasks = List.of(
                    () -> {
                        start.await();
                        return createOrConflict(
                                repository,
                                "concurrent-conflict-key",
                                request("CAPTURE-CONFLICT-A", 3_500L)
                        );
                    },
                    () -> {
                        start.await();
                        return createOrConflict(
                                secondRepository,
                                "concurrent-conflict-key",
                                request("CAPTURE-CONFLICT-B", 3_500L)
                        );
                    }
            );
            start.countDown();
            var futures = executor.invokeAll(tasks);
            Object first = futures.get(0).get();
            Object second = futures.get(1).get();

            assertEquals(1, List.of(first, second).stream()
                    .filter(RefundOperation.class::isInstance)
                    .count());
            assertEquals(1, List.of(first, second).stream()
                    .filter(IdempotencyKeyException.class::isInstance)
                    .count());
            assertEquals(3_500L, jdbc.queryForObject(
                    "select reserved_cents from budget_days",
                    Long.class
            ));
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void concurrentReservationsNeverExceedDailyLimit() throws Exception {
        var executor = Executors.newFixedThreadPool(2);
        try {
            List<Callable<Boolean>> tasks = List.of(
                    () -> reserve("budget-key-a", "CAPTURE-A", 150_000L),
                    () -> reserve("budget-key-b", "CAPTURE-B", 150_000L)
            );
            List<Boolean> results = executor.invokeAll(tasks).stream()
                    .map(future -> {
                        try {
                            return future.get();
                        } catch (Exception exception) {
                            return false;
                        }
                    })
                    .toList();

            assertEquals(1, results.stream().filter(Boolean::booleanValue).count());
            Long reserved = jdbc.queryForObject(
                    """
                    select reserved_cents from budget_days
                    where mandate_id = 'payguard-demo-mandate'
                    """,
                    Long.class
            );
            assertEquals(150_000L, reserved);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void settlementAndReleaseAreIdempotent() {
        RefundOperation success = repository.getOrCreate(
                "settle-key",
                request("CAPTURE-SETTLE", 3_500L),
                approvedDecision(RefundState.APPROVED)
        );
        repository.beginAutomaticExecution(success.operationId());
        repository.complete(
                success.operationId(),
                mapper.map(response("REFUND-SETTLE", "COMPLETED"))
        );
        repository.complete(
                success.operationId(),
                mapper.map(response("REFUND-SETTLE", "COMPLETED"))
        );

        Long committed = jdbc.queryForObject(
                "select committed_cents from budget_days",
                Long.class
        );
        assertEquals(3_500L, committed);

        RefundOperation failure = repository.getOrCreate(
                "release-key",
                request("CAPTURE-RELEASE", 4_000L),
                approvedDecision(RefundState.APPROVED)
        );
        repository.beginAutomaticExecution(failure.operationId());
        repository.complete(
                failure.operationId(),
                mapper.map(response("REFUND-RELEASE", "FAILED"))
        );
        assertEquals(
                0L,
                jdbc.queryForObject(
                        "select reserved_cents from budget_days",
                        Long.class
                )
        );
    }

    @Test
    void captureGuardBlocksPendingButAllowsRetryAfterDefiniteFailureOrDenial() {
        repository.getOrCreate(
                "capture-approved-key",
                request("CAPTURE-GUARD-APPROVED", 3_500L),
                approvedDecision(RefundState.APPROVED)
        );
        assertThrows(
                CaptureRefundConflictException.class,
                () -> repository.getOrCreate(
                        "capture-approved-second-key",
                        request("CAPTURE-GUARD-APPROVED", 3_500L),
                        approvedDecision(RefundState.APPROVED)
                )
        );

        RefundOperation pending = repository.getOrCreate(
                "capture-pending-key",
                request("CAPTURE-GUARD-PENDING", 47_500L),
                approvedDecision(RefundState.PENDING_APPROVAL)
        );
        assertThrows(
                CaptureRefundConflictException.class,
                () -> repository.getOrCreate(
                        "capture-pending-second-key",
                        request("CAPTURE-GUARD-PENDING", 3_500L),
                        approvedDecision(RefundState.APPROVED)
                )
        );

        RefundOperation failed = repository.getOrCreate(
                "capture-failed-key",
                request("CAPTURE-GUARD-FAILED", 3_500L),
                approvedDecision(RefundState.APPROVED)
        );
        repository.beginAutomaticExecution(failed.operationId());
        repository.complete(
                failed.operationId(),
                mapper.map(response("REFUND-GUARD-FAILED", "FAILED"))
        );
        RefundOperation retryAfterFailure = repository.getOrCreate(
                "capture-failed-retry-key",
                request("CAPTURE-GUARD-FAILED", 3_500L),
                approvedDecision(RefundState.APPROVED)
        );
        assertNotEquals(failed.operationId(), retryAfterFailure.operationId());

        repository.getOrCreate(
                "capture-denied-key",
                request("CAPTURE-GUARD-DENIED", 60_000L),
                approvedDecision(RefundState.DENIED)
        );
        RefundOperation retryAfterDenial = repository.getOrCreate(
                "capture-denied-retry-key",
                request("CAPTURE-GUARD-DENIED", 3_500L),
                approvedDecision(RefundState.APPROVED)
        );
        assertEquals(RefundState.APPROVED, retryAfterDenial.state());
    }

    @Test
    void staleInFlightRecoveryRetainsReservation() {
        RefundOperation operation = repository.getOrCreate(
                "stale-key",
                request("CAPTURE-STALE", 4_000L),
                approvedDecision(RefundState.APPROVED)
        );
        repository.beginAutomaticExecution(operation.operationId());
        int recovered = repository.recoverStaleOperations(
                Instant.now().plusSeconds(300)
        );

        assertEquals(1, recovered);
        assertEquals(
                RefundState.RECONCILIATION_REQUIRED,
                repository.findByIdempotencyKey("stale-key").state()
        );
        assertEquals(
                4_000L,
                jdbc.queryForObject(
                        "select reserved_cents from budget_days",
                        Long.class
                )
        );
        assertEquals(
                1L,
                jdbc.queryForObject(
                        "select count(*) from audit_events where operation_id = ?",
                        Long.class,
                        UUID.fromString(operation.operationId())
                )
        );
    }

    @Test
    void concurrentApprovalsAllowOnlyOneExecution() throws Exception {
        RefundOperation operation = repository.getOrCreate(
                "approval-race-key",
                request("CAPTURE-APPROVAL", 47_500L),
                approvedDecision(RefundState.PENDING_APPROVAL)
        );
        JdbcRefundOperationRepository secondRepository =
                new JdbcRefundOperationRepository(dataSource, clock);
        var executor = Executors.newFixedThreadPool(2);
        try {
            List<Callable<Boolean>> tasks = List.of(
                    () -> repository.approve(
                            operation.approvalId(),
                            approver("approver-a")
                    ),
                    () -> secondRepository.approve(
                            operation.approvalId(),
                            approver("approver-b")
                    )
            );
            List<Boolean> results = executor.invokeAll(tasks).stream()
                    .map(future -> {
                        try {
                            return future.get();
                        } catch (Exception exception) {
                            return false;
                        }
                    })
                    .toList();

            assertEquals(1, results.stream().filter(Boolean::booleanValue).count());
            assertEquals(
                    RefundState.IN_FLIGHT,
                    repository.findByApprovalId(operation.approvalId()).state()
            );
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void ambiguousProviderOutcomeRetainsReservation() {
        RefundOperation operation = repository.getOrCreate(
                "ambiguous-key",
                request("CAPTURE-AMBIGUOUS", 4_000L),
                approvedDecision(RefundState.APPROVED)
        );
        repository.beginAutomaticExecution(operation.operationId());
        repository.complete(
                operation.operationId(),
                mapper.mapAmbiguousTransportFailure()
        );

        assertEquals(
                RefundState.RECONCILIATION_REQUIRED,
                repository.findByIdempotencyKey("ambiguous-key").state()
        );
        assertEquals(
                4_000L,
                jdbc.queryForObject(
                        "select reserved_cents from budget_days",
                        Long.class
                )
        );
        assertThrows(
                CaptureRefundConflictException.class,
                () -> repository.getOrCreate(
                        "ambiguous-retry-key",
                        request("CAPTURE-AMBIGUOUS", 4_000L),
                        approvedDecision(RefundState.APPROVED)
                )
        );
    }

    @Test
    void structured422IssuesRemainAmbiguousAndBlockNewKeys() {
        List<String> issues = List.of(
                "REFUND_AMOUNT_EXCEEDED",
                "CAPTURE_NOT_REFUNDABLE",
                "CURRENCY_MISMATCH"
        );
        for (int index = 0; index < issues.size(); index++) {
            int currentIndex = index;
            String captureId = "CAPTURE-422-" + currentIndex;
            String baseUrl = "https://api-m.sandbox.paypal.com";
            RestClient.Builder builder = RestClient.builder().baseUrl(baseUrl);
            MockRestServiceServer server =
                    MockRestServiceServer.bindTo(builder).build();
            server.expect(requestTo(baseUrl + "/v1/oauth2/token"))
                    .andRespond(withSuccess(
                            "{\"access_token\":\"test-token\"}",
                            MediaType.APPLICATION_JSON
                    ));
            server.expect(requestTo(baseUrl
                            + "/v2/payments/captures/" + captureId + "/refund"))
                    .andRespond(withStatus(HttpStatus.UNPROCESSABLE_ENTITY)
                            .contentType(MediaType.APPLICATION_JSON)
                            .body("""
                                    {"name":"UNPROCESSABLE_ENTITY",
                                     "debug_id":"provider-debug",
                                     "details":[{"issue":"%s"}]}
                                    """.formatted(issues.get(index))));

            PayPalClient client = PayPalClientTestSupport.withTransport(
                    builder.build()
            );
            RefundService service = refundService(client);
            RefundRequest request = request(captureId, 3_500L);
            RefundExecutionResult result = service.refund(
                    request, "structured-422-" + currentIndex
            );

            assertEquals(RefundState.RECONCILIATION_REQUIRED, result.state());
            assertReservation(result.operationId(), "RESERVED");
            assertEquals(3_500L * (currentIndex + 1), budgetReservedCents());
            assertEquals(1L, countCaptureBlockingOperations(captureId));
            assertThrows(
                    CaptureRefundConflictException.class,
                    () -> service.refund(
                            request, "structured-422-retry-" + currentIndex
                    )
            );
            assertEquals(1L, countOperationsForCapture(captureId));
            server.verify();
        }
    }

    @Test
    void tokenFailureIsPersistedAsNotDispatchedAndReleasesReservation() {
        AtomicInteger providerCalls = new AtomicInteger();
        PayPalRefundOutcomeMapper outcomeMapper =
                new PayPalRefundOutcomeMapper();
        PayPalRefundGateway gateway = (captureId, amount, currency, requestId) -> {
            if (providerCalls.getAndIncrement() == 0) {
                throw new PayPalRefundException(
                        outcomeMapper.mapNotDispatched()
                );
            }
            return response("REFUND-AFTER-TOKEN-FAILURE", "COMPLETED");
        };
        RefundService service = refundService(gateway);

        RefundExecutionResult result = service.refund(
                request("CAPTURE-TOKEN-FAILURE", 3_500L),
                "token-failure-operation"
        );

        assertEquals(RefundState.FAILED, result.state());
        assertEquals("PAYPAL_TOKEN_UNAVAILABLE", result.error());
        assertReservation(result.operationId(), "RELEASED");
        assertEquals(0L, budgetReservedCents());
        assertEquals(
                0L,
                countCaptureBlockingOperations("CAPTURE-TOKEN-FAILURE")
        );
        RefundExecutionResult retry = service.refund(
                request("CAPTURE-TOKEN-FAILURE", 3_500L),
                "token-failure-retry"
        );
        assertEquals(RefundState.EXECUTED, retry.state());
        assertReservation(retry.operationId(), "SETTLED");
        assertEquals(2, providerCalls.get());
    }

    @Test
    void uncertainProviderOutcomesRetainReservationAndBlockCaptureRetry() {
        PayPalRefundOutcomeMapper outcomeMapper =
                new PayPalRefundOutcomeMapper();
        for (int failureCase = 0; failureCase < 6; failureCase++) {
            int currentCase = failureCase;
            String captureId = "CAPTURE-UNCERTAIN-" + currentCase;
            AtomicInteger providerCalls = new AtomicInteger();
            PayPalRefundGateway gateway = (capture, amount, currency, requestId) -> {
                providerCalls.incrementAndGet();
                return switch (currentCase) {
                    case 0 -> throw new PayPalRefundException(
                            outcomeMapper.mapHttpFailure(422)
                    );
                    case 1 -> throw new PayPalRefundException(
                            outcomeMapper.mapHttpFailure(400)
                    );
                    case 2 -> throw new PayPalRefundException(
                            outcomeMapper.mapHttpFailure(503)
                    );
                    case 3 -> throw new ResourceAccessException(
                            "connection reset"
                    );
                    case 4 -> throw new ResourceAccessException(
                            "read timed out",
                            new SocketTimeoutException("read timed out")
                    );
                    default -> null;
                };
            };
            RefundService service = refundService(gateway);
            RefundRequest request = request(captureId, 3_500L);
            String key = "uncertain-outcome-" + currentCase;

            RefundExecutionResult result = service.refund(request, key);

            assertEquals(
                    RefundState.RECONCILIATION_REQUIRED,
                    result.state(),
                    "case " + currentCase
            );
            assertReservation(result.operationId(), "RESERVED");
            assertThrows(
                    CaptureRefundConflictException.class,
                    () -> service.refund(
                            request, "uncertain-retry-" + currentCase
                    )
            );
            assertEquals(1, providerCalls.get());
            assertEquals(
                    1L,
                    countCaptureBlockingOperations(captureId)
            );
        }
        assertEquals(21_000L, budgetReservedCents());
    }

    @Test
    void typeInvalidHttp200ResponsesRemainReservedAndBlockNewKeys() {
        List<String> invalidResponses = List.of(
                "{\"id\":123,\"status\":\"FAILED\"}",
                "{\"id\":\"CAPTURE-TYPE-INVALID\",\"status\":false}"
        );
        for (int index = 0; index < invalidResponses.size(); index++) {
            int currentIndex = index;
            String captureId = "CAPTURE-TYPE-INVALID-" + currentIndex;
            String body = invalidResponses.get(currentIndex);
            String baseUrl = "https://api-m.sandbox.paypal.com";
            AtomicInteger refundCalls = new AtomicInteger();
            RestClient.Builder builder = RestClient.builder().baseUrl(baseUrl);
            MockRestServiceServer server =
                    MockRestServiceServer.bindTo(builder).build();
            server.expect(requestTo(baseUrl + "/v1/oauth2/token"))
                    .andRespond(withSuccess(
                            "{\"access_token\":\"test-token\"}",
                            MediaType.APPLICATION_JSON
                    ));
            server.expect(requestTo(baseUrl
                            + "/v2/payments/captures/" + captureId + "/refund"))
                    .andRespond(request -> {
                        refundCalls.incrementAndGet();
                        return withSuccess(body, MediaType.APPLICATION_JSON)
                                .createResponse(request);
                    });

            PayPalClient client = PayPalClientTestSupport.withTransport(
                    builder.build()
            );
            RefundService service = refundService(client);
            RefundRequest request = request(captureId, 3_500L);
            RefundExecutionResult result = service.refund(
                    request, "type-invalid-response-" + currentIndex
            );

            assertEquals(
                    RefundState.RECONCILIATION_REQUIRED,
                    result.state(),
                    body
            );
            assertEquals(
                    RefundState.RECONCILIATION_REQUIRED,
                    repository.findByOperationId(result.operationId()).state()
            );
            assertReservation(result.operationId(), "RESERVED");
            assertEquals(
                    3_500L * (currentIndex + 1), budgetReservedCents()
            );
            assertEquals(0L, budgetCommittedCents());
            assertEquals(1L, countCaptureBlockingOperations(captureId));
            assertEquals(1L, countOperationsForCapture(captureId));
            assertThrows(
                    CaptureRefundConflictException.class,
                    () -> service.refund(
                            request, "type-invalid-response-retry-"
                                    + currentIndex
                    )
            );
            assertEquals(1, refundCalls.get());
            assertEquals(1L, jdbc.queryForObject(
                    """
                    select count(*) from budget_reservations
                     where operation_id = ? and status = 'RESERVED'
                    """,
                    Long.class,
                    UUID.fromString(result.operationId())
            ));
            assertEquals(
                    1L,
                    jdbc.queryForObject(
                            "select count(*) from refund_operations where capture_id = ?",
                            Long.class,
                            captureId
                    )
            );
            server.verify();
        }
    }

    @Test
    void concurrentSameKeyRefundLoserReloadsInFlightWinnerState()
            throws Exception {
        CyclicBarrier claimBarrier = new CyclicBarrier(2);
        RefundOperationRepository firstRepository =
                new ClaimBarrierRepository(dataSource, clock, claimBarrier);
        RefundOperationRepository secondRepository =
                new ClaimBarrierRepository(dataSource, clock, claimBarrier);
        CountDownLatch providerStarted = new CountDownLatch(1);
        CountDownLatch releaseProvider = new CountDownLatch(1);
        CountDownLatch losingCallerReturned = new CountDownLatch(1);
        AtomicInteger providerCalls = new AtomicInteger();
        AtomicReference<RefundExecutionResult> losingResult =
                new AtomicReference<>();
        PayPalRefundGateway gateway = (captureId, amount, currency, requestId) -> {
            providerCalls.incrementAndGet();
            providerStarted.countDown();
            try {
                if (!releaseProvider.await(10, TimeUnit.SECONDS)) {
                    throw new IllegalStateException(
                            "Timed out waiting to release PayPal test response"
                    );
                }
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(exception);
            }
            return response("REFUND-CONCURRENT-SERVICE", "COMPLETED");
        };
        RefundService firstService = refundService(gateway, firstRepository);
        RefundService secondService = refundService(gateway, secondRepository);
        RefundRequest request = request("CAPTURE-SERVICE-RACE", 3_500L);
        String key = "same-key-service-race";
        var executor = Executors.newFixedThreadPool(2);
        try {
            CountDownLatch start = new CountDownLatch(1);
            Callable<RefundExecutionResult> firstCall = () -> {
                start.await();
                RefundExecutionResult result =
                        firstService.refund(request, key);
                if (result.state() == RefundState.IN_FLIGHT) {
                    losingResult.set(result);
                    losingCallerReturned.countDown();
                }
                return result;
            };
            Callable<RefundExecutionResult> secondCall = () -> {
                start.await();
                RefundExecutionResult result =
                        secondService.refund(request, key);
                if (result.state() == RefundState.IN_FLIGHT) {
                    losingResult.set(result);
                    losingCallerReturned.countDown();
                }
                return result;
            };
            var first = executor.submit(firstCall);
            var second = executor.submit(secondCall);
            start.countDown();

            assertTrue(providerStarted.await(10, TimeUnit.SECONDS));
            assertTrue(losingCallerReturned.await(10, TimeUnit.SECONDS));
            assertEquals(RefundState.IN_FLIGHT, losingResult.get().state());
            assertEquals(
                    RefundState.IN_FLIGHT,
                    repository.findByIdempotencyKey(key).state()
            );
            assertEquals(1, providerCalls.get());

            releaseProvider.countDown();
            List<RefundExecutionResult> results = List.of(
                    first.get(10, TimeUnit.SECONDS),
                    second.get(10, TimeUnit.SECONDS)
            );
            assertEquals(
                    1,
                    results.stream()
                            .filter(result -> result.state()
                                    == RefundState.EXECUTED)
                            .count()
            );
            assertEquals(1, providerCalls.get());
            assertEquals(3_500L, budgetCommittedCents());
            assertEquals(0L, budgetReservedCents());
        } finally {
            releaseProvider.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void jdbcAuditSinkPersistsAppendOnlyFacts() {
        JdbcAuditSink sink = new JdbcAuditSink(dataSource);
        sink.record(new AuditEvent(
                null,
                UUID.randomUUID().toString(),
                RefundState.DENIED,
                "policy denial",
                "test-agent",
                "AGENT",
                "payguard-demo-mandate",
                null,
                "CAPTURE-AUDIT",
                60000L,
                "USD",
                null,
                null,
                "ABOVE_HUMAN_APPROVAL_LIMIT",
                Instant.now()
        ));

        assertEquals(
                1L,
                jdbc.queryForObject(
                        "select count(*) from audit_events",
                        Long.class
                )
        );
    }

    private boolean reserve(String key, String captureId, long amount) {
        try {
            repository.getOrCreate(
                    key,
                    request(captureId, amount),
                    approvedDecision(RefundState.APPROVED)
            );
            return true;
        } catch (BudgetExceededException exception) {
            return false;
        }
    }

    private Object createOrConflict(
            JdbcRefundOperationRepository repository,
            String key,
            RefundRequest request
    ) {
        try {
            return repository.getOrCreate(
                    key,
                    request,
                    approvedDecision(RefundState.APPROVED)
            );
        } catch (IdempotencyKeyException exception) {
            return exception;
        }
    }

    private RefundDecision approvedDecision(RefundState state) {
        return new RefundDecision(
                state,
                state == RefundState.PENDING_APPROVAL
                        ? "HUMAN_APPROVAL_REQUIRED"
                        : "WITHIN_AUTO_APPROVAL_LIMIT",
                new PolicyTrace(List.of()),
                new BudgetDecisionResult(
                        BudgetDecisionResult.Status.ALLOWED,
                        "DAILY_BUDGET_RESERVED_TRANSACTIONALLY_IN_POSTGRES"
                ),
                UUID.randomUUID().toString()
        );
    }

    private RefundRequest request(String captureId, long amount) {
        return new RefundRequest(
                captureId,
                amount,
                "USD",
                Instant.now().minusSeconds(60),
                AgentContext.authenticatedAgent(
                        "test-agent",
                        "payguard-demo-mandate"
                )
        );
    }

    private ApproverContext approver(String approverId) {
        return new ApproverContext(
                approverId,
                "payguard-demo-mandate",
                true
        );
    }

    private tools.jackson.databind.JsonNode response(
            String refundId,
            String status
    ) {
        var node = tools.jackson.databind.json.JsonMapper.builder()
                .build().createObjectNode();
        node.put("id", refundId);
        node.put("status", status);
        return node;
    }

    private RefundService refundService(PayPalRefundGateway gateway) {
        return refundService(gateway, repository);
    }

    private RefundService refundService(
            PayPalRefundGateway gateway,
            RefundOperationRepository operationRepository
    ) {
        return new RefundService(
                gateway,
                operationRepository,
                new DemoBudgetDecision(),
                new JdbcAuditSink(dataSource),
                clock,
                new DataSourceTransactionManager(dataSource)
        );
    }

    private void assertReservation(String operationId, String expected) {
        assertEquals(
                expected,
                jdbc.queryForObject(
                        """
                        select status from budget_reservations
                         where operation_id = ?
                        """,
                        String.class,
                        UUID.fromString(operationId)
                )
        );
    }

    private long budgetReservedCents() {
        Long value = jdbc.queryForObject(
                """
                select coalesce(sum(reserved_cents), 0)
                  from budget_days
                 where mandate_id = 'payguard-demo-mandate'
                """,
                Long.class
        );
        return value == null ? 0 : value;
    }

    private long budgetCommittedCents() {
        Long value = jdbc.queryForObject(
                """
                select coalesce(sum(committed_cents), 0)
                  from budget_days
                 where mandate_id = 'payguard-demo-mandate'
                """,
                Long.class
        );
        return value == null ? 0 : value;
    }

    private long countCaptureBlockingOperations(String captureId) {
        return jdbc.queryForObject(
                """
                select count(*) from refund_operations
                 where capture_id = ?
                   and state in (
                       'PENDING_APPROVAL', 'APPROVED', 'IN_FLIGHT',
                       'EXECUTED', 'RECONCILIATION_REQUIRED'
                   )
                """,
                Long.class,
                captureId
        );
    }

    private long countOperationsForCapture(String captureId) {
        return jdbc.queryForObject(
                """
                select count(*) from refund_operations where capture_id = ?
                """,
                Long.class,
                captureId
        );
    }

    private static final class ClaimBarrierRepository
            extends JdbcRefundOperationRepository {

        private final CyclicBarrier claimBarrier;

        private ClaimBarrierRepository(
                DriverManagerDataSource dataSource,
                Clock clock,
                CyclicBarrier claimBarrier
        ) {
            super(dataSource, clock);
            this.claimBarrier = claimBarrier;
        }

        @Override
        public boolean beginAutomaticExecution(String operationId) {
            try {
                claimBarrier.await(10, TimeUnit.SECONDS);
            } catch (Exception exception) {
                throw new IllegalStateException(
                        "Concurrent callers did not reach the execution claim",
                        exception
                );
            }
            return super.beginAutomaticExecution(operationId);
        }
    }
}
