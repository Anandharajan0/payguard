package com.anandharajan.payguard;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(properties = {
        "payguard.security.agent-username=test-agent",
        "payguard.security.agent-password=test-agent-secret",
        "payguard.security.approver-username=test-approver",
        "payguard.security.approver-password=test-approver-secret",
        "payguard.security.approver-mandate-id=test-mandate",
        "payguard.budget.mode=demo-unenforced"
})
class PayguardApplicationTests {

    @Test
    void contextLoads() {
    }
}
