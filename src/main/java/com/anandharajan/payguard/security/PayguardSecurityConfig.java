package com.anandharajan.payguard.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
@EnableWebSecurity
public class PayguardSecurityConfig {

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
                .csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session
                        .sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/refunds/approvals/**").hasRole("APPROVER")
                        .requestMatchers("/refunds/**").hasRole("AGENT")
                        .requestMatchers("/resolution-cases/**").hasRole("AGENT")
                        .anyRequest().denyAll())
                .httpBasic(httpBasic -> {});
        return http.build();
    }

    @Bean
    UserDetailsService userDetailsService(
            @Value("${payguard.security.agent-username}") String agentUsername,
            @Value("${payguard.security.agent-password}") String agentPassword,
            @Value("${payguard.security.approver-username}") String approverUsername,
            @Value("${payguard.security.approver-password}") String approverPassword,
            @Value("${payguard.security.client-mode}") String clientMode,
            @Value("${payguard.security.approver-mandate-id}") String approverMandateId,
            PasswordEncoder passwordEncoder
    ) {
        requireConfigured("payguard.security.agent-username", agentUsername);
        requireConfigured("payguard.security.agent-password", agentPassword);
        requireConfigured("payguard.security.approver-username", approverUsername);
        requireConfigured("payguard.security.approver-password", approverPassword);
        requireConfigured("payguard.security.approver-mandate-id", approverMandateId);
        if (!"machine-to-machine".equals(clientMode)) {
            throw new IllegalStateException(
                    "Only machine-to-machine API security is supported in this slice"
            );
        }
        if (agentUsername.equals(approverUsername)) {
            throw new IllegalStateException(
                    "Agent and approver identities must be distinct"
            );
        }

        UserDetails agent = User.withUsername(agentUsername)
                .password(passwordEncoder.encode(agentPassword))
                .roles("AGENT")
                .build();
        UserDetails approver = User.withUsername(approverUsername)
                .password(passwordEncoder.encode(approverPassword))
                .roles("APPROVER")
                .build();
        return new InMemoryUserDetailsManager(agent, approver);
    }

    @Bean
    PasswordEncoder passwordEncoder() {
        return PasswordEncoderFactories.createDelegatingPasswordEncoder();
    }

    private static void requireConfigured(String property, String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(
                    property + " must be configured; insecure access is disabled"
            );
        }
    }
}
