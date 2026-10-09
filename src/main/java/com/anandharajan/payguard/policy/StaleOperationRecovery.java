package com.anandharajan.payguard.policy;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.time.Clock;

@Component
@ConditionalOnProperty(
        name = "payguard.persistence.mode",
        havingValue = "postgres",
        matchIfMissing = true
)
public final class StaleOperationRecovery {

    private final RefundOperationRepository repository;
    private final Clock clock;

    public StaleOperationRecovery(
            RefundOperationRepository repository,
            Clock clock
    ) {
        this.repository = repository;
        this.clock = clock;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void recover() {
        repository.recoverStaleOperations(clock.instant());
    }
}
