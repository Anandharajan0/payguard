package com.anandharajan.payguard.refund;

import com.anandharajan.payguard.audit.InMemoryAuditSink;
import com.anandharajan.payguard.paypal.PayPalRefundGateway;
import com.anandharajan.payguard.policy.RefundOperationStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "payguard.security.agent-username=test-agent",
        "payguard.security.agent-password=test-agent-secret",
        "payguard.security.approver-username=test-approver",
        "payguard.security.approver-password=test-approver-secret",
        "payguard.security.approver-mandate-id=payguard-demo-mandate",
        "payguard.budget.mode=demo-unenforced",
        "payguard.persistence.mode=in-memory",
        "spring.autoconfigure.exclude="
                + "org.springframework.boot.jdbc.autoconfigure."
                + "DataSourceAutoConfiguration,"
                + "org.springframework.boot.flyway.autoconfigure."
                + "FlywayAutoConfiguration"
})
class RefundControllerSecurityTest {

    @Autowired
    private WebApplicationContext applicationContext;

    @Autowired
    private InMemoryAuditSink auditSink;

    @Autowired
    private RefundOperationStore operationStore;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(applicationContext)
                .apply(SecurityMockMvcConfigurers.springSecurity())
                .build();
    }

    @Test
    void missingAgentCredentialsAreRejected() throws Exception {
        mockMvc.perform(post("/refunds")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Idempotency-Key", "http-key-1")
                        .content(validBody()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void invalidAgentCredentialsAreRejected() throws Exception {
        mockMvc.perform(post("/refunds")
                        .with(httpBasic("test-agent", "wrong"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Idempotency-Key", "http-key-2")
                        .content(validBody()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void agentCanSubmitRefundButCannotApprove() throws Exception {
        mockMvc.perform(post("/refunds")
                        .with(httpBasic("test-agent", "test-agent-secret"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Idempotency-Key", "http-key-3")
                        .content(validBody()))
                .andExpect(status().isOk());

        mockMvc.perform(post("/refunds/approvals/approval-1/approve")
                        .with(httpBasic("test-agent", "test-agent-secret")))
                .andExpect(status().isForbidden());
    }

    @Test
    void separateApproverCanApprovePendingRefund() throws Exception {
        mockMvc.perform(post("/refunds")
                        .with(httpBasic("test-agent", "test-agent-secret"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Idempotency-Key", "http-approval")
                        .content("""
                                {
                                  "captureId":"CAPTURE-475",
                                  "amountCents":47500,
                                  "currency":"USD",
                                  "transactionCreatedAt":"2026-10-01T00:00:00Z"
                                }
                                """))
                .andExpect(status().isOk());

        String approvalId = operationStore
                .findByIdempotencyKey("http-approval")
                .approvalId();

        mockMvc.perform(post("/refunds/approvals/{approvalId}/approve", approvalId)
                        .with(httpBasic("test-approver", "test-approver-secret")))
                .andExpect(status().isOk());
    }

    @Test
    void requestBodyCannotSupplyAuthority() throws Exception {
        mockMvc.perform(post("/refunds/evaluate")
                        .with(httpBasic("test-agent", "test-agent-secret"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "captureId":"CAPTURE-1",
                                  "amountCents":3500,
                                  "currency":"USD",
                                  "transactionCreatedAt":"2026-10-01T00:00:00Z",
                                  "agentContext":{
                                    "agentId":"attacker",
                                    "authenticated":true,
                                    "merchantMandate":{
                                      "mandateId":"attacker-mandate",
                                      "status":"ACTIVE"
                                    }
                                  }
                                }
                                """))
                .andExpect(status().isOk());

        var event = auditSink.events().getLast();
        assertEquals("test-agent", event.actorId());
        assertEquals("payguard-demo-mandate", event.mandateId());
    }

    @Test
    void resolutionCasesAreAgentOnly() throws Exception {
        mockMvc.perform(post("/resolution-cases")
                        .with(httpBasic("test-agent", "test-agent-secret"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "customerIssue":"Customer was charged twice and wants a refund",
                                  "captureId":"CAPTURE-35",
                                  "idempotencyKey":"resolution-http"
                                }
                                """))
                .andExpect(status().isOk());

        mockMvc.perform(post("/resolution-cases")
                        .with(httpBasic("test-approver", "test-approver-secret"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "customerIssue":"Customer was charged twice and wants a refund",
                                  "captureId":"CAPTURE-35",
                                  "idempotencyKey":"resolution-http-2"
                                }
                                """))
                .andExpect(status().isForbidden());
    }

    private static String validBody() {
        return """
                {
                  "captureId":"CAPTURE-1",
                  "amountCents":3500,
                  "currency":"USD",
                  "transactionCreatedAt":"2026-10-01T00:00:00Z"
                }
                """;
    }

    @TestConfiguration
    static class TestPayPalConfiguration {
        @Bean
        @Primary
        PayPalRefundGateway payPalRefundGateway() {
            return mock(PayPalRefundGateway.class);
        }
    }
}
