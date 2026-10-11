package com.anandharajan.payguard.policy;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.flywaydb.core.api.FlywayException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.postgresql.util.PSQLException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.sql.Timestamp;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@EnabledIfEnvironmentVariable(
        named = "PAYGUARD_TEST_POSTGRES_URL",
        matches = ".+"
)
class CaptureRefundGuardMigrationTest {

    private static final String URL = System.getenv(
            "PAYGUARD_TEST_POSTGRES_URL"
    );
    private final String hookSuffix =
            UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    private final String schema = "capture_guard_" +
            UUID.randomUUID().toString().replace("-", "");
    private final String eventTriggerName =
            "pause_capture_index_" + hookSuffix;
    private final long migrationPauseKey =
            UUID.randomUUID().getMostSignificantBits();
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
    private boolean eventTriggerInstalled;

    @AfterEach
    void dropIsolatedSchema() {
        if (eventTriggerInstalled) {
            jdbc.execute(
                    "drop event trigger \"" + eventTriggerName + "\""
            );
            eventTriggerInstalled = false;
        }
        jdbc.execute("drop schema if exists \"" + schema + "\" cascade");
    }

    @Test
    void cleanInstallationCreatesIndexAndRejectsSecondBlockingOperation() {
        migrateToLatest();

        assertTrue(indexExists());
        insertOperation("CAPTURE-CLEAN", RefundState.PENDING_APPROVAL);
        assertThrows(
                org.springframework.dao.DuplicateKeyException.class,
                () -> insertOperation("CAPTURE-CLEAN", RefundState.APPROVED)
        );
    }

    @Test
    void legacyBlockingDuplicatesAbortV4WithoutChangingFinancialHistory() {
        migrateThroughV3();
        UUID executedId = UUID.randomUUID();
        UUID ambiguousId = UUID.randomUUID();
        seedBudgetDay(3_500, 3_500);
        insertOperation("CAPTURE-LEGACY-DUPLICATE",
                RefundState.EXECUTED, executedId, 3_500);
        insertOperation("CAPTURE-LEGACY-DUPLICATE",
                RefundState.RECONCILIATION_REQUIRED, ambiguousId, 3_500);
        insertReservation(executedId, "SETTLED", 3_500);
        insertReservation(ambiguousId, "RESERVED", 3_500);
        insertAudit(executedId, RefundState.EXECUTED);
        insertAudit(ambiguousId, RefundState.RECONCILIATION_REQUIRED);

        List<Map<String, Object>> operationsBefore = operationRows();
        List<Map<String, Object>> reservationsBefore = reservationRows();
        List<Map<String, Object>> auditBefore = auditRows();

        FlywayException failure = assertThrows(
                FlywayException.class,
                this::migrateToLatest
        );
        String diagnostic = diagnostics(failure);
        assertTrue(diagnostic.contains("CAPTURE-LEGACY-DUPLICATE"));
        assertTrue(diagnostic.contains(executedId.toString()));
        assertTrue(diagnostic.contains(ambiguousId.toString()));
        assertTrue(diagnostic.contains("EXECUTED"));
        assertTrue(diagnostic.contains("RECONCILIATION_REQUIRED"));
        assertTrue(diagnostic.contains("PayPal"));
        assertTrue(diagnostic.contains("approval"));
        assertTrue(diagnostic.contains("reservation"));
        assertTrue(diagnostic.contains("audit"));

        assertFalse(indexExists());
        assertEquals(0L, jdbc.queryForObject(
                "select count(*) from \"" + schema
                        + "\".flyway_schema_history where version = '4'",
                Long.class
        ));
        assertEquals(operationsBefore, operationRows());
        assertEquals(reservationsBefore, reservationRows());
        assertEquals(auditBefore, auditRows());
    }

    @Test
    void nonBlockingHistoryCanShareCaptureAndV4StillBlocksAnotherActiveRow() {
        migrateThroughV3();
        insertOperation("CAPTURE-HISTORY", RefundState.FAILED);
        insertOperation("CAPTURE-HISTORY", RefundState.DENIED);
        insertOperation("CAPTURE-HISTORY", RefundState.PENDING_APPROVAL);

        migrateToLatest();

        assertTrue(indexExists());
        assertThrows(
                org.springframework.dao.DuplicateKeyException.class,
                () -> insertOperation("CAPTURE-HISTORY", RefundState.EXECUTED)
        );
    }

    @Test
    void migrationWaitsForWriterAlreadyHoldingAWriteLock() throws Exception {
        migrateThroughV3();
        UUID operationId = UUID.randomUUID();
        var executor = Executors.newSingleThreadExecutor();
        try (Connection legacyWriter = dataSource.getConnection()) {
            legacyWriter.setAutoCommit(false);
            insertLegacyBlockingOperation(
                    legacyWriter,
                    "CAPTURE-WRITER-BEFORE-LOCK",
                    operationId
            );

            Future<?> migration = executor.submit(this::migrateToLatest);
            assertTrue(awaitMigrationTableLockWait(),
                    "V4 did not wait for the existing legacy writer");
            assertTrue(!migration.isDone(),
                    "V4 completed before the earlier writer committed");

            legacyWriter.commit();
            migration.get(10, TimeUnit.SECONDS);
        } finally {
            executor.shutdownNow();
        }

        assertTrue(indexExists());
        assertEquals(
                1L,
                jdbc.queryForObject(
                        "select count(*) from \"" + schema
                                + "\".refund_operations where operation_id = ?",
                        Long.class,
                        operationId
                )
        );
    }

    @Test
    void writerAfterPreflightWaitsUntilIndexIsCommitted() throws Exception {
        migrateThroughV3();
        String captureId = "CAPTURE-LOCKED-WRITER";
        insertOperation(captureId, RefundState.APPROVED);
        installIndexCreationPauseHook();

        var executor = Executors.newFixedThreadPool(2);
        try (Connection pauseGate = dataSource.getConnection();
             Connection competingWriter = dataSource.getConnection()) {
            try (var statement = pauseGate.createStatement()) {
                statement.execute(
                        "select pg_advisory_lock(" + migrationPauseKey + ")"
                );
            }

            Future<?> migration = executor.submit(this::migrateToLatest);
            int migrationPid = awaitMigrationPausedAtIndex();
            assertTrue(migrationPid > 0,
                    "V4 did not reach index creation while holding its table lock");

            int writerPid;
            try (var statement = competingWriter.createStatement();
                 var result = statement.executeQuery("select pg_backend_pid()")) {
                result.next();
                writerPid = result.getInt(1);
            }
            UUID competingOperationId = UUID.randomUUID();
            Future<?> insert = executor.submit(() -> {
                insertLegacyBlockingOperation(
                        competingWriter, captureId, competingOperationId
                );
                return null;
            });
            assertTrue(awaitWriterWaiting(writerPid),
                    "The post-preflight writer did not queue on the locked table");
            assertTrue(!insert.isDone(),
                    "The writer completed before V4 created its index");

            try (var statement = pauseGate.createStatement()) {
                statement.execute(
                        "select pg_advisory_unlock(" + migrationPauseKey + ")"
                );
            }
            migration.get(10, TimeUnit.SECONDS);

            ExecutionException writerFailure = assertThrows(
                    ExecutionException.class,
                    () -> insert.get(10, TimeUnit.SECONDS)
            );
            assertTrue(hasUniqueViolation(writerFailure),
                    "The waiting writer must resume into the committed unique index");
            assertTrue(indexExists());
            assertEquals(
                    1L,
                    jdbc.queryForObject(
                            "select count(*) from \"" + schema
                                    + "\".refund_operations where capture_id = ?",
                            Long.class,
                            captureId
                    )
            );
        } finally {
            try (Connection unlock = dataSource.getConnection();
                 var statement = unlock.createStatement()) {
                statement.execute(
                        "select pg_advisory_unlock(" + migrationPauseKey + ")"
                );
            } finally {
                executor.shutdownNow();
                if (eventTriggerInstalled) {
                    jdbc.execute(
                            "drop event trigger \"" + eventTriggerName + "\""
                    );
                    eventTriggerInstalled = false;
                }
            }
        }
    }

    private void installIndexCreationPauseHook() {
        jdbc.execute("""
                create function "%s".pause_before_capture_index()
                returns event_trigger
                language plpgsql
                as $payguard$
                begin
                    if current_schema() = '%s' then
                        perform pg_advisory_lock(%d);
                        perform pg_advisory_unlock(%d);
                    end if;
                end;
                $payguard$
                """.formatted(schema, schema,
                migrationPauseKey, migrationPauseKey));
        jdbc.execute("""
                create event trigger "%s"
                on ddl_command_start
                when tag in ('CREATE INDEX')
                execute function "%s".pause_before_capture_index()
                """.formatted(eventTriggerName, schema));
        eventTriggerInstalled = true;
    }

    private int awaitMigrationPausedAtIndex() throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline) {
            Integer pid = jdbc.query(
                    """
                    select table_lock.pid
                      from pg_locks table_lock
                      join pg_class relation on relation.oid = table_lock.relation
                      join pg_namespace namespace
                        on namespace.oid = relation.relnamespace
                     where namespace.nspname = ?
                       and relation.relname = 'refund_operations'
                       and table_lock.mode = 'ShareRowExclusiveLock'
                       and table_lock.granted
                       and exists (
                           select 1 from pg_locks advisory_lock
                            where advisory_lock.pid = table_lock.pid
                              and advisory_lock.locktype = 'advisory'
                              and advisory_lock.granted = false
                       )
                     limit 1
                    """,
                    result -> result.next() ? result.getInt(1) : null,
                    schema
            );
            if (pid != null) {
                return pid;
            }
            Thread.sleep(10);
        }
        return -1;
    }

    private boolean awaitWriterWaiting(int writerPid)
            throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline) {
            Boolean waiting = jdbc.queryForObject(
                    """
                    select exists (
                        select 1
                          from pg_locks locks
                          join pg_class relation on relation.oid = locks.relation
                          join pg_namespace namespace
                            on namespace.oid = relation.relnamespace
                         where namespace.nspname = ?
                           and relation.relname = 'refund_operations'
                           and locks.pid = ?
                           and locks.mode = 'RowExclusiveLock'
                           and not locks.granted
                    )
                    """,
                    Boolean.class,
                    schema,
                    writerPid
            );
            if (Boolean.TRUE.equals(waiting)) {
                return true;
            }
            Thread.sleep(10);
        }
        return false;
    }

    private static boolean hasUniqueViolation(Throwable failure) {
        for (Throwable cause = failure; cause != null;
             cause = cause.getCause()) {
            if (cause instanceof SQLException sqlException
                    && "23505".equals(sqlException.getSQLState())) {
                return true;
            }
        }
        return false;
    }

    private void migrateThroughV3() {
        flyway(MigrationVersion.fromVersion("3")).migrate();
    }

    private void migrateToLatest() {
        flyway(MigrationVersion.LATEST).migrate();
    }

    private Flyway flyway(MigrationVersion target) {
        return Flyway.configure()
                .dataSource(dataSource)
                .schemas(schema)
                .defaultSchema(schema)
                .target(target)
                .locations("classpath:db/migration")
                .load();
    }

    private boolean indexExists() {
        return Boolean.TRUE.equals(jdbc.queryForObject(
                """
                select exists (
                    select 1 from pg_indexes
                     where schemaname = ? and indexname = ?
                )
                """,
                Boolean.class,
                schema,
                "refund_operations_one_active_per_capture"
        ));
    }

    private void insertOperation(String captureId, RefundState state) {
        insertOperation(captureId, state, UUID.randomUUID(), 3_500);
    }

    private void insertOperation(
            String captureId,
            RefundState state,
            UUID operationId,
            long amountCents
    ) {
        Instant now = Instant.parse("2026-10-10T00:00:00Z");
        jdbc.update(
                """
                insert into "%s".refund_operations (
                    operation_id, mandate_id, idempotency_key,
                    request_fingerprint, capture_id, amount_cents, currency,
                    transaction_created_at, requester_agent_id, decision_state,
                    decision_reason, budget_status, budget_explanation,
                    state, approval_id, correlation_id, provider_request_id,
                    provider_refund_id, provider_status, error_code,
                    created_at, updated_at, completed_at
                ) values (?, 'payguard-demo-mandate', ?, ?, ?, ?, 'USD',
                          ?, 'migration-test-agent', ?, 'MIGRATION_TEST',
                          'ALLOWED', 'migration test budget',
                          ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """.formatted(schema),
                operationId,
                "migration-" + operationId,
                "a".repeat(64),
                captureId,
                amountCents,
                Timestamp.from(now.minusSeconds(60)),
                state.name(),
                state.name(),
                state == RefundState.PENDING_APPROVAL
                        ? UUID.randomUUID() : null,
                UUID.randomUUID(),
                "provider-request-" + operationId,
                state == RefundState.EXECUTED
                        ? "provider-refund-" + operationId : null,
                state == RefundState.EXECUTED ? "COMPLETED" : null,
                state == RefundState.RECONCILIATION_REQUIRED
                        ? "PAYPAL_OUTCOME_AMBIGUOUS" : null,
                Timestamp.from(now),
                Timestamp.from(now),
                isCompleted(state) ? Timestamp.from(now) : null
        );
    }

    private void insertLegacyBlockingOperation(
            Connection connection,
            String captureId,
            UUID operationId
    ) throws SQLException {
        Instant now = Instant.parse("2026-10-10T00:00:00Z");
        try (PreparedStatement statement = connection.prepareStatement(
                """
                insert into "%s".refund_operations (
                    operation_id, mandate_id, idempotency_key,
                    request_fingerprint, capture_id, amount_cents, currency,
                    transaction_created_at, requester_agent_id, decision_state,
                    decision_reason, budget_status, budget_explanation,
                    state, correlation_id, provider_request_id, created_at,
                    updated_at
                ) values (?, 'payguard-demo-mandate', ?, ?, ?, 3500, 'USD',
                          ?, 'migration-test-agent', 'APPROVED',
                          'MIGRATION_TEST', 'ALLOWED', 'migration test budget',
                          'APPROVED', ?, ?, ?, ?)
                """.formatted(schema)
        )) {
            statement.setObject(1, operationId);
            statement.setString(2, "migration-writer-" + operationId);
            statement.setString(3, "b".repeat(64));
            statement.setString(4, captureId);
            statement.setTimestamp(5, Timestamp.from(now.minusSeconds(60)));
            statement.setObject(6, UUID.randomUUID());
            statement.setString(7, "provider-request-" + operationId);
            statement.setTimestamp(8, Timestamp.from(now));
            statement.setTimestamp(9, Timestamp.from(now));
            statement.executeUpdate();
        }
    }

    private boolean awaitMigrationTableLockWait() throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline) {
            Boolean waiting = jdbc.queryForObject(
                    """
                    select exists (
                        select 1
                          from pg_locks locks
                          join pg_class relation on relation.oid = locks.relation
                          join pg_namespace namespace
                            on namespace.oid = relation.relnamespace
                         where namespace.nspname = ?
                           and relation.relname = 'refund_operations'
                           and locks.mode = 'ShareRowExclusiveLock'
                           and not locks.granted
                    )
                    """,
                    Boolean.class,
                    schema
            );
            if (Boolean.TRUE.equals(waiting)) {
                return true;
            }
            Thread.sleep(20);
        }
        return false;
    }

    private void seedBudgetDay(long reservedCents, long committedCents) {
        jdbc.update(
                """
                insert into "%s".budget_days (
                    mandate_id, budget_date, daily_limit_cents,
                    reserved_cents, committed_cents
                ) values ('payguard-demo-mandate', '2026-10-10', 200000, ?, ?)
                """.formatted(schema),
                reservedCents,
                committedCents
        );
    }

    private void insertReservation(
            UUID operationId,
            String status,
            long amountCents
    ) {
        jdbc.update(
                """
                insert into "%s".budget_reservations (
                    operation_id, mandate_id, budget_date, amount_cents,
                    status, created_at, updated_at, released_at, settled_at
                ) values (?, 'payguard-demo-mandate', '2026-10-10', ?,
                          ?, now(), now(), null, ?)
                """.formatted(schema),
                operationId,
                amountCents,
                status,
                "SETTLED".equals(status) ? Timestamp.from(Instant.now()) : null
        );
    }

    private void insertAudit(UUID operationId, RefundState state) {
        jdbc.update(
                """
                insert into "%s".audit_events (
                    operation_id, correlation_id, state, explanation,
                    actor_id, actor_role, mandate_id, capture_id,
                    amount_cents, currency, paypal_refund_id, paypal_status,
                    error_code, occurred_at
                ) values (?, ?, ?, 'migration history', 'migration-test',
                          'SYSTEM', 'payguard-demo-mandate',
                          'CAPTURE-LEGACY-DUPLICATE', 3500, 'USD',
                          null, null, null, now())
                """.formatted(schema),
                operationId,
                UUID.randomUUID(),
                state.name()
        );
    }

    private List<Map<String, Object>> operationRows() {
        return jdbc.queryForList(
                """
                select operation_id, mandate_id, idempotency_key,
                       request_fingerprint, capture_id, amount_cents,
                       state, approval_id, correlation_id, provider_request_id,
                       provider_refund_id, provider_status, error_code,
                       created_at, updated_at, completed_at
                  from "%s".refund_operations
                 order by operation_id
                """.formatted(schema)
        );
    }

    private List<Map<String, Object>> reservationRows() {
        return jdbc.queryForList(
                """
                select operation_id, mandate_id, budget_date, amount_cents,
                       status, created_at, updated_at, released_at, settled_at
                  from "%s".budget_reservations order by operation_id
                """.formatted(schema)
        );
    }

    private List<Map<String, Object>> auditRows() {
        return jdbc.queryForList(
                """
                select event_id, operation_id, correlation_id, state,
                       explanation, actor_id, actor_role, mandate_id,
                       capture_id, amount_cents, currency, paypal_refund_id,
                       paypal_status, error_code, occurred_at
                  from "%s".audit_events order by event_id
                """.formatted(schema)
        );
    }

    private static boolean isCompleted(RefundState state) {
        return state == RefundState.EXECUTED
                || state == RefundState.FAILED
                || state == RefundState.DENIED;
    }

    private static String diagnostics(Throwable failure) {
        StringBuilder result = new StringBuilder();
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            result.append(cause).append('\n');
            if (cause instanceof PSQLException postgres
                    && postgres.getServerErrorMessage() != null) {
                result.append(postgres.getServerErrorMessage().getMessage())
                        .append('\n')
                        .append(postgres.getServerErrorMessage().getDetail())
                        .append('\n')
                        .append(postgres.getServerErrorMessage().getHint())
                        .append('\n');
            }
        }
        return result.toString();
    }
}
