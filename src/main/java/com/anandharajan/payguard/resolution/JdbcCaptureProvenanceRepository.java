package com.anandharajan.payguard.resolution;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Timestamp;
import java.util.List;

@Component
@ConditionalOnProperty(
        name = "payguard.persistence.mode",
        havingValue = "postgres",
        matchIfMissing = true
)
public final class JdbcCaptureProvenanceRepository
        implements CaptureProvenanceRepository {

    private final JdbcTemplate jdbc;

    public JdbcCaptureProvenanceRepository(DataSource dataSource) {
        this.jdbc = new JdbcTemplate(dataSource);
    }

    @Override
    public CaptureProvenance find(String captureId) {
        List<CaptureProvenance> rows = jdbc.query(
                """
                select capture_id, mandate_id, amount_cents, currency,
                       order_id, registered_at, no_external_refunds_confirmed
                  from paypal_capture_provenance
                 where capture_id = ?
                """,
                (rs, rowNum) -> new CaptureProvenance(
                        rs.getString("capture_id"),
                        rs.getString("mandate_id"),
                        rs.getLong("amount_cents"),
                        rs.getString("currency"),
                        rs.getString("order_id"),
                        rs.getTimestamp("registered_at").toInstant(),
                        rs.getBoolean("no_external_refunds_confirmed")
                ),
                captureId
        );
        return rows.isEmpty() ? null : rows.getFirst();
    }

    @Override
    public void register(CaptureProvenance provenance) {
        jdbc.update(
                """
                insert into paypal_capture_provenance (
                    capture_id, mandate_id, amount_cents, currency, order_id,
                    registered_at, no_external_refunds_confirmed
                ) values (?, ?, ?, ?, ?, ?, ?)
                on conflict (capture_id) do nothing
                """,
                provenance.captureId(),
                provenance.mandateId(),
                provenance.amountCents(),
                provenance.currency(),
                provenance.orderId(),
                Timestamp.from(provenance.registeredAt()),
                provenance.noExternalRefundsConfirmed()
        );
        CaptureProvenance stored = find(provenance.captureId());
        if (!provenance.equals(stored)) {
            throw new IllegalStateException(
                    "Capture provenance cannot be overwritten"
            );
        }
    }
}
