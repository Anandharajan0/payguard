package com.anandharajan.payguard.policy;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.time.Instant;

@Component
@ConditionalOnProperty(
        name = "payguard.persistence.mode",
        havingValue = "postgres",
        matchIfMissing = true
)
public final class JdbcMandateInitializer {

    private final JdbcTemplate jdbc;
    private final String mandateId;
    private final long dailyLimitCents;

    public JdbcMandateInitializer(
            DataSource dataSource,
            @Value("${payguard.security.mandate-id}") String mandateId,
            @Value("${payguard.budget.daily-limit-cents:200000}")
            long dailyLimitCents
    ) {
        this.jdbc = new JdbcTemplate(dataSource);
        this.mandateId = mandateId;
        this.dailyLimitCents = dailyLimitCents;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void initialize() {
        Instant now = Instant.now();
        jdbc.update(
                """
                insert into merchant_mandates (
                    mandate_id, status, daily_limit_cents, created_at, updated_at
                ) values (?, 'ACTIVE', ?, ?, ?)
                on conflict (mandate_id) do nothing
                """,
                mandateId,
                dailyLimitCents,
                now,
                now
        );
    }
}
