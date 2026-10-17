package com.anandharajan.payguard.resolution;

import com.anandharajan.payguard.paypal.PayPalClient;
import com.anandharajan.payguard.paypal.PayPalLookupException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.List;

@Component
@ConditionalOnProperty(
        name = "payguard.resolution.evidence-mode",
        havingValue = "paypal"
)
public final class PayPalTransactionEvidenceProvider
        implements TransactionEvidenceProvider {

    private final PayPalClient payPal;
    private final CaptureProvenanceRepository provenance;
    private final Clock clock;
    private final String configuredMandateId;

    public PayPalTransactionEvidenceProvider(
            PayPalClient payPal,
            CaptureProvenanceRepository provenance,
            Clock clock,
            @Value("${payguard.security.mandate-id}") String configuredMandateId
    ) {
        this.payPal = payPal;
        this.provenance = provenance;
        this.clock = clock;
        this.configuredMandateId = configuredMandateId;
    }

    @Override
    public TransactionEvidence find(String captureId) {
        if (captureId == null || captureId.isBlank()) {
            return null;
        }
        CaptureProvenance registered = provenance.find(captureId);
        if (registered == null
                || !configuredMandateId.equals(registered.mandateId())) {
            return null;
        }
        JsonNode response;
        try {
            response = payPal.capture(captureId);
        } catch (PayPalLookupException exception) {
            return null;
        }
        return parse(response, registered, clock);
    }

    public PayPalRefundDetails findRefund(String refundId) {
        if (refundId == null || refundId.isBlank()) {
            return null;
        }
        try {
            return PayPalRefundDetails.parse(payPal.refundDetails(refundId));
        } catch (PayPalLookupException exception) {
            return null;
        }
    }

    static TransactionEvidence parse(
            JsonNode response,
            CaptureProvenance registered
    ) {
        return parse(response, registered, Clock.systemUTC());
    }

    private static TransactionEvidence parse(
            JsonNode response,
            CaptureProvenance registered,
            Clock clock
    ) {
        if (response == null || !response.isObject()) {
            return null;
        }
        String returnedId = text(response, "id");
        String status = text(response, "status");
        JsonNode amount = response.get("amount");
        String value = amount == null ? null : text(amount, "value");
        String currency = amount == null ? null
                : text(amount, "currency_code");
        Instant capturedAt = timestamp(response, "create_time");
        if (capturedAt == null) {
            capturedAt = timestamp(response, "update_time");
        }
        if (!registered.captureId().equals(returnedId)
                || !"COMPLETED".equals(status)
                || value == null
                || currency == null
                || capturedAt == null
                || !registered.currency().equals(currency)
                || !validMoney(value)
                || cents(value) != registered.amountCents()) {
            return null;
        }
        return new TransactionEvidence(
                returnedId,
                registered.amountCents(),
                currency,
                capturedAt,
                registered.mandateId(),
                status,
                0,
                List.of(),
                "paypal-capture-unverified-v2",
                Instant.now(clock)
        );
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value != null && value.isTextual() && !value.asText().isBlank()
                ? value.asText()
                : null;
    }

    private static Instant timestamp(JsonNode node, String field) {
        String value = text(node, field);
        if (value == null) {
            return null;
        }
        try {
            return OffsetDateTime.parse(value).toInstant();
        } catch (DateTimeParseException exception) {
            return null;
        }
    }

    private static boolean validMoney(String value) {
        return value.matches("[0-9]+(\\.[0-9]{1,2})?");
    }

    private static long cents(String value) {
        return new java.math.BigDecimal(value)
                .movePointRight(2).longValueExact();
    }

    public record PayPalRefundDetails(
            String refundId,
            String status,
            Instant updatedAt
    ) {
        static PayPalRefundDetails parse(JsonNode response) {
            if (response == null || !response.isObject()) {
                return null;
            }
            JsonNode id = response.get("id");
            JsonNode status = response.get("status");
            if (id == null || !id.isTextual() || id.asText().isBlank()
                    || status == null || !status.isTextual()
                    || status.asText().isBlank()) {
                return null;
            }
            Instant updatedAt = null;
            JsonNode updated = response.get("update_time");
            if (updated != null && updated.isTextual()) {
                try {
                    updatedAt = OffsetDateTime.parse(
                            updated.asText()
                    ).toInstant();
                } catch (DateTimeParseException ignored) {
                    return null;
                }
            }
            return new PayPalRefundDetails(
                    id.asText(), status.asText(), updatedAt
            );
        }
    }
}
