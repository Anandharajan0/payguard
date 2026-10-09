package com.anandharajan.payguard.policy;

import com.anandharajan.payguard.paypal.PayPalRefundOutcomeMapper;
import com.anandharajan.payguard.audit.AuditEvent;
import com.anandharajan.payguard.audit.JdbcAuditSink;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

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
}
