package com.anandharajan.payguard;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Primary;
import com.anandharajan.payguard.audit.AuditSink;
import com.anandharajan.payguard.audit.JdbcAuditSink;
import com.anandharajan.payguard.policy.JdbcRefundOperationRepository;
import com.anandharajan.payguard.policy.RefundOperationRepository;
import javax.sql.DataSource;

import java.time.Clock;

@Configuration
public class PayguardConfiguration {
    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    @ConditionalOnProperty(
            name = "payguard.persistence.mode",
            havingValue = "postgres",
            matchIfMissing = true
    )
    @Primary
    RefundOperationRepository jdbcRefundOperationRepository(
            DataSource dataSource,
            Clock clock
    ) {
        return new JdbcRefundOperationRepository(dataSource, clock);
    }

    @Bean
    @ConditionalOnProperty(
            name = "payguard.persistence.mode",
            havingValue = "postgres",
            matchIfMissing = true
    )
    @Primary
    AuditSink jdbcAuditSink(DataSource dataSource) {
        return new JdbcAuditSink(dataSource);
    }

    @Bean
    com.anandharajan.payguard.policy.BudgetDecision budgetDecision(
            @Value("${payguard.budget.mode:fail-closed}") String mode,
            @Value("${payguard.persistence.mode:postgres}") String persistenceMode
    ) {
        return switch (mode) {
            case "fail-closed" ->
                    "postgres".equals(persistenceMode)
                            ? new com.anandharajan.payguard.policy.DatabaseBudgetDecision()
                            : new com.anandharajan.payguard.policy.NoOpBudgetDecision();
            case "demo-unenforced" ->
                    new com.anandharajan.payguard.policy.DemoBudgetDecision();
            default -> throw new IllegalStateException(
                    "Unsupported payguard.budget.mode: " + mode
            );
        };
    }
}
