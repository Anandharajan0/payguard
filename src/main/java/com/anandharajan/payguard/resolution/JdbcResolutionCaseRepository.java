package com.anandharajan.payguard.resolution;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.UncategorizedSQLException;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

import javax.sql.DataSource;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;

@Component
@ConditionalOnProperty(
        name = "payguard.persistence.mode",
        havingValue = "postgres",
        matchIfMissing = true
)
public final class JdbcResolutionCaseRepository
        implements ResolutionCaseRepository {

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final JsonMapper json = JsonMapper.builder().build();

    public JdbcResolutionCaseRepository(DataSource dataSource) {
        this.jdbc = new JdbcTemplate(dataSource);
        this.transactions = new TransactionTemplate(
                new DataSourceTransactionManager(dataSource)
        );
    }

    @Override
    public ResolutionCase findByKey(String mandateId, String idempotencyKey) {
        List<ResolutionCase> rows = jdbc.query(
                """
                select * from resolution_cases
                 where mandate_id = ? and idempotency_key = ?
                """,
                (rs, rowNum) -> map(rs),
                mandateId, idempotencyKey
        );
        return rows.isEmpty() ? null : rows.getFirst();
    }

    @Override
    public ResolutionCaseClaim claim(
            String mandateId,
            String idempotencyKey,
            String requestFingerprint,
            String customerIssue,
            TransactionEvidence evidence
    ) {
        String caseId = UUID.randomUUID().toString();
        String claimToken = UUID.randomUUID().toString();
        String evidenceJson = write(evidence);
        int inserted = jdbc.update(
                """
                insert into resolution_cases (
                    case_id, mandate_id, idempotency_key, request_fingerprint,
                    customer_issue, evidence_snapshot, status,
                    processing_claim_token, processing_lease_expires_at,
                    created_at, updated_at
                ) values (?, ?, ?, ?, ?, cast(? as jsonb), 'PROCESSING',
                          ?, now() + interval '30 seconds', now(), now())
                on conflict (mandate_id, idempotency_key) do nothing
                """,
                UUID.fromString(caseId), mandateId, idempotencyKey,
                requestFingerprint, customerIssue, evidenceJson,
                UUID.fromString(claimToken)
        );
        ResolutionCase resolutionCase = load(mandateId, idempotencyKey);
        if (!resolutionCase.requestFingerprint().equals(requestFingerprint)) {
            throw new IllegalArgumentException(
                    "Resolution idempotency key was reused for a different request"
            );
        }
        int reclaimed = 0;
        if (inserted == 0
                && resolutionCase.status() == ResolutionCaseStatus.PROCESSING
                && resolutionCase.proposal() == null) {
            reclaimed = jdbc.update(
                    """
                    update resolution_cases
                       set processing_claim_token = ?,
                           processing_lease_expires_at =
                               now() + interval '30 seconds',
                           updated_at = now()
                     where case_id = ? and status = 'PROCESSING'
                       and model_proposal is null
                       and (processing_lease_expires_at is null
                            or processing_lease_expires_at < now())
                    """,
                    UUID.fromString(claimToken),
                    UUID.fromString(resolutionCase.caseId())
            );
        }
        boolean claimed = inserted == 1 || reclaimed == 1;
        resolutionCase = load(mandateId, idempotencyKey);
        return new ResolutionCaseClaim(
                resolutionCase, claimed, claimed ? claimToken : null
        );
    }

    @Override
    public ResolutionCase processProposal(
            String caseId,
            String claimToken,
            Function<ResolutionCase, ResolutionProposalResult> processor
    ) {
        try {
            return transactions.execute(transaction -> {
                List<ResolutionCase> rows = jdbc.query(
                        """
                        select * from resolution_cases
                         where case_id = ? for update nowait
                        """,
                        (rs, rowNum) -> map(rs),
                        UUID.fromString(caseId)
                );
                if (rows.isEmpty()) {
                    throw new IllegalArgumentException(
                            "Resolution case was not found"
                    );
                }
                ResolutionCase current = rows.getFirst();
                if (current.status() != ResolutionCaseStatus.PROCESSING
                        || !claimToken.equals(current.processingClaimToken())
                        || current.proposal() != null) {
                    return current;
                }
                ResolutionProposalResult result = processor.apply(current);
                int updated = jdbc.update(
                        """
                        update resolution_cases
                           set model_proposal = cast(? as jsonb),
                               status = ?, failure_code = ?,
                               processing_claim_token = null,
                               processing_lease_expires_at = null,
                               updated_at = now()
                         where case_id = ? and status = 'PROCESSING'
                           and model_proposal is null
                        """,
                        result.proposal() == null
                                ? null : write(result.proposal()),
                        result.status().name(),
                        result.failureCode(),
                        UUID.fromString(caseId)
                );
                if (updated != 1) {
                    throw new IllegalStateException(
                            "Resolution proposal ownership was lost"
                    );
                }
                return load(caseId);
            });
        } catch (DataAccessException busy) {
            if (busy instanceof UncategorizedSQLException sqlException
                    && "55P03".equals(
                            sqlException.getSQLException().getSQLState())) {
                return load(caseId);
            }
            throw busy;
        }
    }

    @Override
    public ResolutionCase linkOperation(String caseId, String operationId) {
        if (operationId == null) {
            throw new IllegalArgumentException(
                    "A refund operation ID is required"
            );
        }
        int updated = jdbc.update(
                """
                update resolution_cases
                   set operation_id = ?, status = 'REFUND_SUBMITTED',
                       processing_claim_token = null,
                       processing_lease_expires_at = null,
                       updated_at = now()
                 where case_id = ? and (operation_id is null or operation_id = ?)
                """,
                UUID.fromString(operationId),
                UUID.fromString(caseId),
                UUID.fromString(operationId)
        );
        if (updated != 1) {
            throw new IllegalStateException(
                    "Resolution case is linked to a different refund operation"
            );
        }
        return load(caseId);
    }

    private ResolutionCase load(String mandateId, String idempotencyKey) {
        List<ResolutionCase> rows = jdbc.query(
                """
                select * from resolution_cases
                 where mandate_id = ? and idempotency_key = ?
                """,
                (rs, rowNum) -> map(rs),
                mandateId, idempotencyKey
        );
        if (rows.isEmpty()) {
            throw new IllegalStateException("Resolution case was not persisted");
        }
        return rows.getFirst();
    }

    private ResolutionCase load(String caseId) {
        List<ResolutionCase> rows = jdbc.query(
                "select * from resolution_cases where case_id = ?",
                (rs, rowNum) -> map(rs),
                UUID.fromString(caseId)
        );
        if (rows.isEmpty()) {
            throw new IllegalArgumentException("Resolution case was not found");
        }
        return rows.getFirst();
    }

    private ResolutionCase map(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new ResolutionCase(
                rs.getObject("case_id", UUID.class).toString(),
                rs.getString("mandate_id"),
                rs.getString("idempotency_key"),
                rs.getString("request_fingerprint"),
                rs.getString("customer_issue"),
                read(rs.getString("evidence_snapshot"), TransactionEvidence.class),
                readNullable(rs.getString("model_proposal"), ResolutionProposal.class),
                ResolutionCaseStatus.valueOf(rs.getString("status")),
                rs.getObject("operation_id", UUID.class) == null
                        ? null : rs.getObject("operation_id", UUID.class).toString(),
                rs.getObject("processing_claim_token", UUID.class) == null
                        ? null : rs.getObject("processing_claim_token",
                                UUID.class).toString(),
                rs.getTimestamp("processing_lease_expires_at") == null
                        ? null : rs.getTimestamp(
                                "processing_lease_expires_at").toInstant(),
                rs.getString("failure_code")
        );
    }

    private String write(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (Exception exception) {
            throw new IllegalStateException("Could not serialize resolution record",
                    exception);
        }
    }

    private <T> T read(String value, Class<T> type) {
        try {
            return json.readValue(value, type);
        } catch (Exception exception) {
            throw new IllegalStateException("Could not deserialize resolution record",
                    exception);
        }
    }

    private <T> T readNullable(String value, Class<T> type) {
        return value == null ? null : read(value, type);
    }
}
