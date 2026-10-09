package com.anandharajan.payguard;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.beans.factory.annotation.Value;

import java.time.Clock;

@Configuration
public class PayguardConfiguration {
    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    com.anandharajan.payguard.policy.BudgetDecision budgetDecision(
            @Value("${payguard.budget.mode:fail-closed}") String mode
    ) {
        return switch (mode) {
            case "fail-closed" ->
                    new com.anandharajan.payguard.policy.NoOpBudgetDecision();
            case "demo-unenforced" ->
                    new com.anandharajan.payguard.policy.DemoBudgetDecision();
            default -> throw new IllegalStateException(
                    "Unsupported payguard.budget.mode: " + mode
            );
        };
    }
}
