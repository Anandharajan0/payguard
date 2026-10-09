package com.anandharajan.payguard.security;

import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;

import static org.junit.jupiter.api.Assertions.assertThrows;

class PayguardSecurityConfigTest {

    @Test
    void missingCredentialsFailClosed() {
        PayguardSecurityConfig config = new PayguardSecurityConfig();

        assertThrows(
                IllegalStateException.class,
                () -> config.userDetailsService(
                        "",
                        "",
                        "approver",
                        "approver-secret",
                        "machine-to-machine",
                        "test-mandate",
                        PasswordEncoderFactories.createDelegatingPasswordEncoder()
                )
        );
    }

    @Test
    void agentAndApproverIdentitiesMustBeDistinct() {
        PayguardSecurityConfig config = new PayguardSecurityConfig();

        assertThrows(
                IllegalStateException.class,
                () -> config.userDetailsService(
                        "same-user",
                        "agent-secret",
                        "same-user",
                        "approver-secret",
                        "machine-to-machine",
                        "test-mandate",
                        PasswordEncoderFactories.createDelegatingPasswordEncoder()
                )
        );
    }

    @Test
    void unsupportedBrowserModeFailsClosed() {
        PayguardSecurityConfig config = new PayguardSecurityConfig();

        assertThrows(
                IllegalStateException.class,
                () -> config.userDetailsService(
                        "agent",
                        "agent-secret",
                        "approver",
                        "approver-secret",
                        "browser-dashboard",
                        "test-mandate",
                        PasswordEncoderFactories.createDelegatingPasswordEncoder()
                )
        );
    }

    @Test
    void missingApproverMandateFailsClosed() {
        PayguardSecurityConfig config = new PayguardSecurityConfig();

        assertThrows(
                IllegalStateException.class,
                () -> config.userDetailsService(
                        "agent",
                        "agent-secret",
                        "approver",
                        "approver-secret",
                        "machine-to-machine",
                        "",
                        PasswordEncoderFactories.createDelegatingPasswordEncoder()
                )
        );
    }
}
