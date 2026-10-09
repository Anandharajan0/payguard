package com.anandharajan.payguard.audit;

import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.sql.Timestamp;
import java.util.UUID;

public final class JdbcAuditSink implements AuditSink {

    private final JdbcTemplate jdbc;

    public JdbcAuditSink(DataSource dataSource) {
        this.jdbc = new JdbcTemplate(dataSource);
    }

    @Override
    public void record(AuditEvent event) {
        jdbc.update(
                """
                insert into audit_events (
                    operation_id, correlation_id, state, explanation,
                    actor_id, actor_role, mandate_id, approval_id,
                    capture_id, amount_cents, currency, paypal_refund_id,
                    paypal_status, error_code, occurred_at
                ) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                event.operationId() == null
                        ? null : UUID.fromString(event.operationId()),
                UUID.fromString(event.correlationId()),
                event.state().name(),
                event.explanation(),
                event.actorId(),
                event.actorRole(),
                event.mandateId(),
                event.approvalId() == null
                        ? null : UUID.fromString(event.approvalId()),
                event.captureId(),
                event.amountCents(),
                event.currency(),
                event.paypalRefundId(),
                event.paypalStatus(),
                event.error(),
                Timestamp.from(event.occurredAt())
        );
    }
}
