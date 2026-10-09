package com.anandharajan.payguard.paypal;

import com.anandharajan.payguard.policy.RefundState;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PayPalRefundOutcomeMapperTest {

    private final PayPalRefundOutcomeMapper mapper =
            new PayPalRefundOutcomeMapper();
    private final JsonMapper jsonMapper = JsonMapper.builder().build();

    @Test
    void completedRefundIsExecuted() {
        PayPalRefundOutcome outcome = mapper.map(response("REFUND-1", "COMPLETED"));

        assertEquals(RefundState.EXECUTED, outcome.state());
        assertEquals("REFUND-1", outcome.refundId());
    }

    @Test
    void pendingRefundRequiresReconciliation() {
        PayPalRefundOutcome outcome = mapper.map(response("REFUND-1", "PENDING"));

        assertEquals(RefundState.RECONCILIATION_REQUIRED, outcome.state());
        assertNotEquals(RefundState.EXECUTED, outcome.state());
        assertEquals("PAYPAL_REFUND_PENDING", outcome.error());
    }

    @Test
    void terminalProviderFailuresAreFailed() {
        PayPalRefundOutcome failed = mapper.map(
                response("REFUND-1", "FAILED")
        );
        PayPalRefundOutcome cancelled = mapper.map(
                response("REFUND-1", "CANCELLED")
        );

        assertEquals(RefundState.FAILED, failed.state());
        assertEquals(RefundState.FAILED, cancelled.state());
        assertNotEquals(RefundState.EXECUTED, failed.state());
        assertNotEquals(RefundState.EXECUTED, cancelled.state());
    }

    @Test
    void incompleteAndUnknownResponsesRequireReconciliation() {
        PayPalRefundOutcome missingId = mapper.map(
                response(null, "COMPLETED")
        );
        PayPalRefundOutcome missingStatus = mapper.map(
                response("REFUND-1", null)
        );
        PayPalRefundOutcome unknown = mapper.map(
                response("REFUND-1", "PROCESSING")
        );
        PayPalRefundOutcome missingResponse = mapper.map(null);

        assertEquals(RefundState.RECONCILIATION_REQUIRED, missingId.state());
        assertEquals(RefundState.RECONCILIATION_REQUIRED, missingStatus.state());
        assertEquals(RefundState.RECONCILIATION_REQUIRED, unknown.state());
        assertEquals(
                RefundState.RECONCILIATION_REQUIRED,
                missingResponse.state()
        );
        assertNotEquals(RefundState.EXECUTED, missingId.state());
        assertNotEquals(RefundState.EXECUTED, missingStatus.state());
        assertNotEquals(RefundState.EXECUTED, unknown.state());
        assertNotEquals(RefundState.EXECUTED, missingResponse.state());
    }

    @Test
    void transportFailuresAreMappedWithoutSuccess() {
        PayPalRefundOutcome clientFailure = mapper.mapHttpFailure(422);
        PayPalRefundOutcome providerFailure = mapper.mapHttpFailure(503);
        PayPalRefundOutcome ambiguous = mapper.mapAmbiguousTransportFailure();

        assertEquals(RefundState.FAILED, clientFailure.state());
        assertEquals(RefundState.RECONCILIATION_REQUIRED,
                providerFailure.state());
        assertEquals(RefundState.RECONCILIATION_REQUIRED, ambiguous.state());
        assertNotEquals(RefundState.EXECUTED, clientFailure.state());
        assertNotEquals(RefundState.EXECUTED, providerFailure.state());
        assertNotEquals(RefundState.EXECUTED, ambiguous.state());
    }

    @Test
    void executedOutcomeCannotBeConstructedWithInvalidProviderFacts() {
        assertThrows(
                IllegalArgumentException.class,
                () -> PayPalRefundOutcome.create(
                        RefundState.EXECUTED,
                        null,
                        "COMPLETED",
                        null
                )
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> PayPalRefundOutcome.create(
                        RefundState.EXECUTED,
                        "REFUND-1",
                        "PENDING",
                        null
                )
        );
    }

    private tools.jackson.databind.JsonNode response(
            String refundId,
            String status
    ) {
        var node = jsonMapper.createObjectNode();
        if (refundId != null) {
            node.put("id", refundId);
        }
        if (status != null) {
            node.put("status", status);
        }
        return node;
    }
}
