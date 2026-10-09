package com.anandharajan.payguard.policy;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

final class RefundValidation {

    private static final Pattern CAPTURE_ID = Pattern.compile("[A-Za-z0-9_-]{1,128}");
    private static final Pattern CURRENCY = Pattern.compile("[A-Z]{3}");

    private RefundValidation() {}

    static List<String> validate(
            RefundRequest request,
            RefundPolicy policy,
            Clock clock
    ) {
        List<String> errors = new ArrayList<>();
        if (request == null) {
            return List.of("REFUND_REQUEST_REQUIRED");
        }
        if (request.captureId() == null || !CAPTURE_ID.matcher(request.captureId()).matches()) {
            errors.add("CAPTURE_ID_INVALID");
        }
        if (request.amountCents() == null) {
            errors.add("REFUND_AMOUNT_REQUIRED");
        } else if (request.amountCents() <= 0) {
            errors.add("REFUND_AMOUNT_INVALID");
        }
        if (request.currency() == null || !CURRENCY.matcher(request.currency()).matches()) {
            errors.add("CURRENCY_INVALID");
        }
        if (request.transactionCreatedAt() == null) {
            errors.add("TRANSACTION_DATE_REQUIRED");
        } else {
            Instant now = clock.instant();
            if (request.transactionCreatedAt().isAfter(now)) {
                errors.add("TRANSACTION_DATE_IN_FUTURE");
            }
            if (request.transactionCreatedAt().isBefore(
                    now.minus(policy.maxTransactionAgeDays(), ChronoUnit.DAYS))) {
                errors.add("TRANSACTION_TOO_OLD");
            }
        }
        return List.copyOf(errors);
    }
}
