package com.anandharajan.payguard.resolution;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

@EnabledIfEnvironmentVariable(
        named = "PAYGUARD_TEST_POSTGRES_URL",
        matches = ".+"
)
class JdbcResolutionCaseRepositoryTest {

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
    private final JdbcResolutionCaseRepository repository =
            new JdbcResolutionCaseRepository(dataSource);

    @BeforeEach
    void reset() {
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
    void durableCaseReplaysEvidenceAndRejectsConflictingFingerprint() {
        TransactionEvidence evidence = new TransactionEvidence(
                "CAPTURE-DURABLE",
                3_500,
                "USD",
                Instant.parse("2026-10-01T00:00:00Z"),
                "payguard-demo-mandate",
                "COMPLETED",
                3_500,
                java.util.List.of(),
                "provider-v1",
                Instant.parse("2026-10-10T00:00:00Z")
        );
        ResolutionCaseClaim firstClaim = repository.claim(
                "payguard-demo-mandate",
                "resolution-durable",
                "a".repeat(64),
                "Customer was charged twice",
                evidence
        );
        ResolutionCaseClaim replayClaim = repository.claim(
                "payguard-demo-mandate",
                "resolution-durable",
                "a".repeat(64),
                "Customer was charged twice",
                evidence
        );

        assertEquals(firstClaim.resolutionCase().caseId(),
                replayClaim.resolutionCase().caseId());
        assertEquals(evidence, replayClaim.resolutionCase().evidence());
        assertEquals("Customer was charged twice",
                replayClaim.resolutionCase().customerIssue());
        org.junit.jupiter.api.Assertions.assertTrue(firstClaim.claimed());
        org.junit.jupiter.api.Assertions.assertFalse(replayClaim.claimed());
        assertThrows(
                IllegalArgumentException.class,
                () -> repository.claim(
                        "payguard-demo-mandate",
                        "resolution-durable",
                        "b".repeat(64),
                        "Different issue",
                        evidence
                )
        );
        assertEquals(1L, jdbc.queryForObject(
                "select count(*) from resolution_cases", Long.class
        ));
    }
}
