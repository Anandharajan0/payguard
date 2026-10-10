package com.anandharajan.payguard.policy;

import com.anandharajan.payguard.paypal.PayPalRefundOutcome;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

public class JdbcRefundOperationRepository
        implements RefundOperationRepository {

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final Clock clock;

    public JdbcRefundOperationRepository(DataSource dataSource, Clock clock) {
        this.jdbc = new JdbcTemplate(dataSource);
        this.transactions = new TransactionTemplate(
                new DataSourceTransactionManager(dataSource)
        );
        this.clock = clock;
    }

    @Override
    public RefundOperation getOrCreate(
            String idempotencyKey,
            RefundRequest request,
            RefundDecision decision
    ) {
        return transactions.execute(status -> {
            String mandateId = request.agentContext()
                    .merchantMandate().mandateId();
            String operationId = UUID.randomUUID().toString();
            String approvalId = decision.state() == RefundState.PENDING_APPROVAL
                    ? UUID.randomUUID().toString()
                    : null;
            Instant now = clock.instant();
            String fingerprint = RefundOperationStore.fingerprint(request);
            int inserted = jdbc.update(
                    """
                    insert into refund_operations (
                        operation_id, mandate_id, idempotency_key,
                        request_fingerprint, capture_id, amount_cents, currency,
                        transaction_created_at, requester_agent_id,
                        decision_state, decision_reason, budget_status,
                        budget_explanation, state, approval_id, correlation_id,
                        provider_request_id, created_at, updated_at
                    ) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?,
                              ?, ?, ?, ?)
                    on conflict (mandate_id, idempotency_key) do nothing
                    """,
                    UUID.fromString(operationId),
                    mandateId,
                    idempotencyKey,
                    fingerprint,
                    request.captureId(),
                    request.amountCents(),
                    request.currency(),
                    Timestamp.from(request.transactionCreatedAt()),
                    request.agentContext().agentId(),
                    decision.state().name(),
                    decision.reason(),
                    decision.budgetDecision().status().name(),
                    decision.budgetDecision().explanation(),
                    decision.state().name(),
                    approvalId == null ? null : UUID.fromString(approvalId),
                    UUID.fromString(decision.correlationId()),
                    operationId,
                    Timestamp.from(now),
                    Timestamp.from(now)
            );
            if (inserted == 0) {
                RefundOperation operation = loadByKeyForUpdate(
                        mandateId, idempotencyKey
                );
                if (!operation.requestFingerprint().equals(fingerprint)) {
                    throw new IdempotencyKeyException(
                            "Idempotency-Key was already used for a different refund request"
                    );
                }
                return operation;
            }

            if (decision.state() == RefundState.APPROVED
                    || decision.state() == RefundState.PENDING_APPROVAL) {
                reserve(mandateId, LocalDate.ofInstant(now, ZoneOffset.UTC),
                        request.amountCents(), UUID.fromString(operationId), now);
            }
            return loadById(operationId);
        });
    }

    @Override
    public RefundOperation findByApprovalId(String approvalId) {
        List<RefundOperation> rows = jdbc.query(
                "select * from refund_operations where approval_id = ?",
                mapper(),
                UUID.fromString(approvalId)
        );
        return rows.isEmpty() ? null : rows.getFirst();
    }

    @Override
    public RefundOperation findByIdempotencyKey(String idempotencyKey) {
        List<RefundOperation> rows = jdbc.query(
                "select * from refund_operations where idempotency_key = ?",
                mapper(),
                idempotencyKey
        );
        return rows.isEmpty() ? null : rows.getFirst();
    }

    @Override
    public RefundOperation findByOperationId(String operationId) {
        List<RefundOperation> rows = jdbc.query(
                "select * from refund_operations where operation_id = ?",
                mapper(),
                UUID.fromString(operationId)
        );
        return rows.isEmpty() ? null : rows.getFirst();
    }

    @Override
    public boolean beginAutomaticExecution(String operationId) {
        return transactions.execute(status -> jdbc.update(
                """
                update refund_operations
                set state = 'IN_FLIGHT',
                    lease_expires_at = ?,
                    updated_at = ?
                where operation_id = ? and state = 'APPROVED'
                """,
                Timestamp.from(clock.instant().plusSeconds(120)),
                Timestamp.from(clock.instant()),
                UUID.fromString(operationId)
        ) == 1);
    }

    @Override
    public boolean approve(String approvalId, ApproverContext approver) {
        return transactions.execute(status -> {
            List<RefundOperation> rows = jdbc.query(
                    """
                    select * from refund_operations
                    where approval_id = ? for update
                    """,
                    mapper(),
                    UUID.fromString(approvalId)
            );
            if (rows.isEmpty()) {
                return false;
            }
            RefundOperation operation = rows.getFirst();
            if (!approver.isAuthorizedFor(operation.mandateId())) {
                throw new ApprovalScopeException(
                        "Approver is not authorized for the refund mandate"
                );
            }
            if (operation.requesterAgentId() != null
                    && operation.requesterAgentId().equals(
                    approver.approverId())) {
                throw new SelfApprovalException(
                        "The requesting agent cannot approve its own refund"
                );
            }
            return jdbc.update(
                    """
                    update refund_operations
                    set state = 'IN_FLIGHT',
                        lease_expires_at = ?,
                        updated_at = ?
                    where approval_id = ? and state = 'PENDING_APPROVAL'
                    """,
                    Timestamp.from(clock.instant().plusSeconds(120)),
                    Timestamp.from(clock.instant()),
                    UUID.fromString(approvalId)
            ) == 1;
        });
    }

    @Override
    public RefundOperation complete(
            String operationId,
            PayPalRefundOutcome outcome
    ) {
        return transactions.execute(status -> {
            Instant now = clock.instant();
            int updated = jdbc.update(
                    """
                    update refund_operations
                    set state = ?, provider_refund_id = ?,
                        provider_status = ?, error_code = ?,
                        lease_expires_at = null, completed_at = ?,
                        updated_at = ?
                    where operation_id = ? and state = 'IN_FLIGHT'
                    """,
                    outcome.state().name(),
                    outcome.refundId(),
                    outcome.paypalStatus(),
                    outcome.error(),
                    Timestamp.from(now),
                    Timestamp.from(now),
                    UUID.fromString(operationId)
            );
            if (updated != 1) {
                RefundOperation current = loadById(operationId);
                if (current.state() == outcome.state()
                        && java.util.Objects.equals(
                        current.result().refundId(), outcome.refundId())
                        && java.util.Objects.equals(
                        current.result().paypalStatus(),
                        outcome.paypalStatus())) {
                    return current;
                }
                throw new IllegalStateException(
                        "Only an in-flight operation can be completed"
                );
            }
            if (outcome.state() == RefundState.EXECUTED) {
                settle(operationId, now);
            } else if (outcome.state() == RefundState.FAILED) {
                release(operationId, now);
            }
            return loadById(operationId);
        });
    }

    @Override
    public int recoverStaleOperations(Instant now) {
        return transactions.execute(status -> {
            List<UUID> ids = jdbc.query(
                    """
                    select operation_id from refund_operations
                    where state = 'IN_FLIGHT' and lease_expires_at < ?
                    for update skip locked
                    """,
                    (rs, rowNum) -> rs.getObject(1, UUID.class),
                    Timestamp.from(now)
            );
            for (UUID id : ids) {
                jdbc.update(
                        """
                        update refund_operations
                        set state = 'RECONCILIATION_REQUIRED',
                            error_code = 'STALE_IN_FLIGHT_REQUIRES_RECONCILIATION',
                            lease_expires_at = null, updated_at = ?
                        where operation_id = ? and state = 'IN_FLIGHT'
                        """,
                        Timestamp.from(now), id
                );
                RefundOperation recovered = loadById(id.toString());
                jdbc.update(
                        """
                        insert into audit_events (
                            operation_id, correlation_id, state, explanation,
                            actor_id, actor_role, mandate_id, approval_id,
                            capture_id, amount_cents, currency, error_code,
                            occurred_at
                        ) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """,
                        id,
                        UUID.fromString(recovered.decision().correlationId()),
                        recovered.state().name(),
                        "Stale in-flight operation requires provider reconciliation",
                        "system-recovery",
                        "SYSTEM",
                        recovered.mandateId(),
                        recovered.approvalId() == null
                                ? null : UUID.fromString(recovered.approvalId()),
                        recovered.request().captureId(),
                        recovered.request().amountCents(),
                        recovered.request().currency(),
                        recovered.result().error(),
                        Timestamp.from(now)
                );
            }
            return ids.size();
        });
    }

    private void reserve(
            String mandateId,
            LocalDate date,
            long amount,
            UUID operationId,
            Instant now
    ) {
        Integer active = jdbc.queryForObject(
                "select count(*) from merchant_mandates " +
                        "where mandate_id = ? and status = 'ACTIVE'",
                Integer.class,
                mandateId
        );
        if (active == null || active != 1) {
            throw new IllegalStateException(
                    "Refund mandate is missing or not active: " + mandateId
            );
        }
        jdbc.update(
                """
                insert into budget_days (
                    mandate_id, budget_date, daily_limit_cents
                )
                select mandate_id, ?, daily_limit_cents
                from merchant_mandates where mandate_id = ?
                on conflict (mandate_id, budget_date) do nothing
                """,
                date, mandateId
        );
        int updated = jdbc.update(
                """
                update budget_days
                set reserved_cents = reserved_cents + ?
                where mandate_id = ? and budget_date = ?
                  and reserved_cents + committed_cents + ? <= daily_limit_cents
                """,
                amount, mandateId, date, amount
        );
        if (updated != 1) {
            throw new BudgetExceededException(
                    "Daily refund budget would be exceeded"
            );
        }
        jdbc.update(
                """
                insert into budget_reservations (
                    operation_id, mandate_id, budget_date, amount_cents,
                    status, created_at, updated_at
                ) values (?, ?, ?, ?, 'RESERVED', ?, ?)
                """,
                operationId, mandateId, date, amount,
                Timestamp.from(now), Timestamp.from(now)
        );
    }

    private void settle(String operationId, Instant now) {
        int changed = jdbc.update(
                """
                update budget_reservations
                set status = 'SETTLED', settled_at = ?, updated_at = ?
                where operation_id = ? and status = 'RESERVED'
                """,
                Timestamp.from(now), Timestamp.from(now),
                UUID.fromString(operationId)
        );
        if (changed == 1) {
            jdbc.update(
                    """
                    update budget_days d
                    set reserved_cents = reserved_cents - r.amount_cents,
                        committed_cents = committed_cents + r.amount_cents
                    from budget_reservations r
                    where r.operation_id = ? and d.mandate_id = r.mandate_id
                      and d.budget_date = r.budget_date
                    """,
                    UUID.fromString(operationId)
            );
        }
    }

    private void release(String operationId, Instant now) {
        int changed = jdbc.update(
                """
                update budget_reservations
                set status = 'RELEASED', released_at = ?, updated_at = ?
                where operation_id = ? and status = 'RESERVED'
                """,
                Timestamp.from(now), Timestamp.from(now),
                UUID.fromString(operationId)
        );
        if (changed == 1) {
            jdbc.update(
                    """
                    update budget_days d
                    set reserved_cents = reserved_cents - r.amount_cents
                    from budget_reservations r
                    where r.operation_id = ? and d.mandate_id = r.mandate_id
                      and d.budget_date = r.budget_date
                    """,
                    UUID.fromString(operationId)
            );
        }
    }

    private RefundOperation loadById(String operationId) {
        return jdbc.queryForObject(
                "select * from refund_operations where operation_id = ?",
                mapper(),
                UUID.fromString(operationId)
        );
    }

    private RefundOperation loadByKeyForUpdate(
            String mandateId,
            String idempotencyKey
    ) {
        return jdbc.queryForObject(
                """
                select * from refund_operations
                where mandate_id = ? and idempotency_key = ?
                for update
                """,
                mapper(),
                mandateId,
                idempotencyKey
        );
    }

    private RowMapper<RefundOperation> mapper() {
        return (rs, rowNum) -> {
            RefundState state = RefundState.valueOf(rs.getString("state"));
            RefundDecision decision = new RefundDecision(
                    RefundState.valueOf(rs.getString("decision_state")),
                    rs.getString("decision_reason"),
                    new PolicyTrace(List.of()),
                    new BudgetDecisionResult(
                            BudgetDecisionResult.Status.valueOf(
                                    rs.getString("budget_status")
                            ),
                            rs.getString("budget_explanation")
                    ),
                    rs.getObject("correlation_id", UUID.class).toString()
            );
            RefundRequest request = new RefundRequest(
                    rs.getString("capture_id"),
                    rs.getLong("amount_cents"),
                    rs.getString("currency"),
                    rs.getTimestamp("transaction_created_at").toInstant(),
                    AgentContext.authenticatedAgent(
                            rs.getString("requester_agent_id"),
                            rs.getString("mandate_id")
                    )
            );
            RefundOperation operation = new RefundOperation(
                    rs.getObject("operation_id", UUID.class).toString(),
                    rs.getString("idempotency_key"),
                    rs.getString("request_fingerprint"),
                    request,
                    decision,
                    clock
            );
            operation.restore(
                    state,
                    rs.getObject("approval_id") == null
                            ? null
                            : rs.getObject("approval_id", UUID.class).toString(),
                    rs.getString("provider_refund_id"),
                    rs.getString("provider_status"),
                    rs.getString("error_code")
            );
            return operation;
        };
    }
}
