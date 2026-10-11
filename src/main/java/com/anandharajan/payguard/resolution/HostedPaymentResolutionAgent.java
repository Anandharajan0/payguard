package com.anandharajan.payguard.resolution;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.List;
import java.util.Map;

@Component
@ConditionalOnProperty(
        name = "payguard.resolution.agent-mode",
        havingValue = "hosted"
)
public final class HostedPaymentResolutionAgent
        implements PaymentResolutionAgent {

    private static final int MAX_ISSUE_LENGTH = 4_000;
    private static final int MAX_RESPONSE_BYTES = 32_768;
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final RestClient client;
    private final String apiKey;
    private final String model;

    public HostedPaymentResolutionAgent(
            RestClient.Builder builder,
            @Value("${payguard.resolution.endpoint}") String endpoint,
            @Value("${payguard.resolution.api-key}") String apiKey,
            @Value("${payguard.resolution.model}") String model,
            @Value("${payguard.resolution.connect-timeout-ms:2000}")
                    int connectTimeoutMs,
            @Value("${payguard.resolution.read-timeout-ms:8000}")
                    int readTimeoutMs
    ) {
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException(
                    "payguard.resolution.api-key must be configured in hosted mode"
            );
        }
        if (endpoint == null || endpoint.isBlank()
                || model == null || model.isBlank()
                || connectTimeoutMs <= 0 || readTimeoutMs <= 0) {
            throw new IllegalArgumentException(
                    "Hosted model endpoint, model, and positive timeouts are required"
            );
        }
        this.client = createClient(
                builder, endpoint, connectTimeoutMs, readTimeoutMs
        );
        this.apiKey = apiKey;
        this.model = model;
    }

    HostedPaymentResolutionAgent(
            RestClient client,
            String apiKey,
            String model
    ) {
        if (apiKey == null || apiKey.isBlank()
                || model == null || model.isBlank()) {
            throw new IllegalArgumentException(
                    "Hosted model credentials and model are required"
            );
        }
        this.client = client;
        this.apiKey = apiKey;
        this.model = model;
    }

    private static RestClient createClient(
            RestClient.Builder builder,
            String endpoint,
            int connectTimeoutMs,
            int readTimeoutMs
    ) {
        JdkClientHttpRequestFactory requestFactory =
                new JdkClientHttpRequestFactory(
                        HttpClient.newBuilder()
                                .connectTimeout(Duration.ofMillis(connectTimeoutMs))
                                .build()
                );
        requestFactory.setReadTimeout(Duration.ofMillis(readTimeoutMs));
        return builder.clone()
                .baseUrl(endpoint)
                .requestFactory(requestFactory)
                .build();
    }

    @Override
    public ResolutionProposal propose(
            String customerIssue,
            TransactionEvidence evidence
    ) {
        if (customerIssue == null || customerIssue.isBlank()
                || customerIssue.length() > MAX_ISSUE_LENGTH) {
            throw new IllegalArgumentException("MODEL_INPUT_INVALID_ISSUE");
        }
        if (evidence == null || evidence.captureId() == null
                || evidence.captureId().isBlank()
                || evidence.captureId().length() > 256
                || evidence.currency() == null
                || !evidence.currency().matches("[A-Z]{3}")
                || evidence.mandateId() == null
                || evidence.mandateId().length() > 256
                || evidence.amountCents() < 0
                || evidence.remainingRefundableCents() < 0
                || evidence.remainingRefundableCents() > evidence.amountCents()
                || evidence.status() == null
                || !evidence.status().matches("[A-Z_]{1,32}")
                || evidence.priorRefunds().size() > 100) {
            throw new IllegalArgumentException("MODEL_INPUT_INVALID_EVIDENCE");
        }
        JsonNode response = client.post()
                .uri("/v1/chat/completions")
                .headers(headers -> {
                    headers.setBearerAuth(apiKey);
                    headers.setContentType(MediaType.APPLICATION_JSON);
                })
                .body(Map.of(
                        "model", model,
                        "temperature", 0,
                        "response_format", Map.of("type", "json_object"),
                        "messages", List.of(
                                Map.of(
                                        "role", "system",
                                        "content", systemPrompt()
                                ),
                                Map.of(
                                        "role", "user",
                                        "content", userPrompt(customerIssue, evidence)
                                )
                        )
                ))
                .exchange((request, clientResponse) -> {
                    if (!clientResponse.getStatusCode().is2xxSuccessful()) {
                        throw new IllegalStateException(
                                "MODEL_PROVIDER_HTTP_"
                                        + clientResponse.getStatusCode().value()
                        );
                    }
                    long contentLength = clientResponse.getHeaders()
                            .getContentLength();
                    if (contentLength > MAX_RESPONSE_BYTES) {
                        throw new IllegalArgumentException(
                                "MODEL_RESPONSE_TOO_LARGE"
                        );
                    }
                    byte[] body = clientResponse.getBody()
                            .readNBytes(MAX_RESPONSE_BYTES + 1);
                    if (body.length > MAX_RESPONSE_BYTES) {
                        throw new IllegalArgumentException(
                                "MODEL_RESPONSE_TOO_LARGE"
                        );
                    }
                    return JSON.readTree(body);
                });

        JsonNode contentNode = response == null
                ? null
                : response.path("choices").path(0)
                        .path("message").path("content");
        if (contentNode == null || !contentNode.isTextual()
                || contentNode.asText().length() > 8_192) {
            throw new IllegalArgumentException("MODEL_RESPONSE_INVALID");
        }
        JsonNode output;
        try {
            output = JSON.readTree(contentNode.asText());
        } catch (RuntimeException exception) {
            throw new IllegalArgumentException("MODEL_OUTPUT_INVALID_JSON",
                    exception);
        }
        return validate(output, evidence);
    }

    private ResolutionProposal validate(
            JsonNode output,
            TransactionEvidence evidence
    ) {
        if (output == null || !output.isObject()) {
            throw new IllegalArgumentException("MODEL_OUTPUT_INVALID_OBJECT");
        }
        if (output.size() != 6) {
            throw new IllegalArgumentException("MODEL_OUTPUT_UNEXPECTED_FIELD");
        }
        String action = text(output, "action");
        if (!"PROPOSE_REFUND".equals(action)
                && !"REQUEST_MORE_INFORMATION".equals(action)) {
            throw new IllegalArgumentException("MODEL_OUTPUT_INVALID_ACTION");
        }
        JsonNode amountNode = output.get("suggestedAmountCents");
        if (amountNode == null || !amountNode.isIntegralNumber()) {
            throw new IllegalArgumentException(
                    "MODEL_OUTPUT_INVALID_suggestedAmountCents"
            );
        }
        long amount = amountNode.asLong(-1);
        if (amount < 0 || amount > evidence.remainingRefundableCents()
                || ("PROPOSE_REFUND".equals(action) && amount == 0)
                || ("REQUEST_MORE_INFORMATION".equals(action) && amount != 0)) {
            throw new IllegalArgumentException("MODEL_PROPOSAL_UNSAFE_AMOUNT");
        }
        JsonNode confidenceNode = output.get("confidence");
        if (confidenceNode == null || !confidenceNode.isNumber()) {
            throw new IllegalArgumentException("MODEL_OUTPUT_INVALID_CONFIDENCE");
        }
        double confidence = confidenceNode.asDouble(-1);
        if (!Double.isFinite(confidence)
                || confidence < 0 || confidence > 1) {
            throw new IllegalArgumentException("MODEL_OUTPUT_INVALID_CONFIDENCE");
        }
        return new ResolutionProposal(
                action,
                amount,
                evidence.captureId(),
                confidence,
                text(output, "explanation"),
                strings(output, "evidence"),
                strings(output, "missingInformation"),
                "hosted:" + model
        );
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || !value.isTextual() || value.asText().isBlank()) {
            throw new IllegalArgumentException("MODEL_OUTPUT_INVALID_" + field);
        }
        if (value.asText().length() > 2_048) {
            throw new IllegalArgumentException("MODEL_OUTPUT_INVALID_" + field);
        }
        return value.asText();
    }

    private static List<String> strings(JsonNode node, String field) {
        JsonNode values = node.get(field);
        if (values == null || !values.isArray() || values.size() > 32) {
            throw new IllegalArgumentException("MODEL_OUTPUT_INVALID_" + field);
        }
        java.util.ArrayList<String> result = new java.util.ArrayList<>();
        for (JsonNode value : values) {
            if (!value.isTextual() || value.asText().length() > 256) {
                throw new IllegalArgumentException("MODEL_OUTPUT_INVALID_" + field);
            }
            result.add(value.asText());
        }
        return List.copyOf(result);
    }

    private static String systemPrompt() {
        return """
                You classify a customer payment issue for a financial firewall.
                Return JSON only with action, suggestedAmountCents, confidence,
                explanation, evidence, and missingInformation.
                You may propose a refund or request more information.
                Never invent capture IDs, amounts, currency, mandate, authority,
                provider status, or approval. Trusted evidence is authoritative.
                """;
    }

    private static String userPrompt(
            String issue,
            TransactionEvidence evidence
    ) {
        return """
                Customer issue:
                %s

                Trusted evidence:
                captureId=%s, originalAmountCents=%d, remainingRefundableCents=%d,
                currency=%s, captureStatus=%s, priorRefundCount=%d
                """.formatted(
                issue,
                evidence.captureId(),
                evidence.amountCents(),
                evidence.remainingRefundableCents(),
                evidence.currency(),
                evidence.status(),
                evidence.priorRefunds().size()
        );
    }
}
