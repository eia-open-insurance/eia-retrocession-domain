package com.eia.retrocession.domain;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Client for the FS-RI retro hand-off endpoint (SPEC 7.18.3, 7.23.5).
 *
 * <p>Writes carry the SPEC 7.23.2 envelope, the documented simulator path
 * {@code /sim/fsri/v1/retros}, and an {@code Idempotency-Key} derived from
 * tenant + retro id + transition version. Responses are normalised so the
 * domain only sees a confirmation or a canonical {@link DomainException}:</p>
 *
 * <ul>
 *   <li>2xx (including 207 partial) - hand-off confirmation;</li>
 *   <li>409 - conflict (a same-key replay arrives as 200 with
 *       {@code duplicate}/{@code Idempotent-Replayed});</li>
 *   <li>400/422 - caller-correctable structured 400;</li>
 *   <li>other 4xx - structured 502;</li>
 *   <li>5xx, 429, or timeout - structured 503.</li>
 * </ul>
 */
@Component
public class FsriRetroClient {
    public static final String DEFAULT_BASE_URL = "http://localhost:8181";
    public static final String DEFAULT_HANDOFF_PATH = "/sim/fsri/v1/retros";
    private static final String SOURCE_SYSTEM = "eia-retrocession-domain";
    private static final String TARGET_SYSTEM = "FS-RI";
    private static final String SCHEMA_VERSION = "1.0.0";
    private static final String BUSINESS_BOOK = "ASSUMED";

    private final RestClient client;
    private final ObjectMapper objectMapper;
    private final String handoffPath;

    @Autowired
    public FsriRetroClient(RestClient.Builder builder,
                           ObjectMapper objectMapper,
                           @Value("${eia.fsri.base-url:http://localhost:8181}") String baseUrl,
                           @Value("${eia.fsri.retro-path:/sim/fsri/v1/retros}") String handoffPath,
                           @Value("${eia.fsri.connect-timeout:2s}") Duration connectTimeout,
                           @Value("${eia.fsri.read-timeout:5s}") Duration readTimeout) {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(millis(connectTimeout));
        requestFactory.setReadTimeout(millis(readTimeout));
        this.client = builder.clone()
                .baseUrl(baseUrl)
                .requestFactory(requestFactory)
                .build();
        this.objectMapper = objectMapper;
        this.handoffPath = handoffPath;
    }

    public FsriRetroClient(RestClient.Builder builder, ObjectMapper objectMapper, String baseUrl) {
        this(builder, objectMapper, baseUrl, DEFAULT_HANDOFF_PATH, Duration.ofSeconds(2), Duration.ofSeconds(5));
    }

    /**
     * Idempotency key required by SPEC 7.18.3 HAND-01 and 7.23.3 INT-01.
     * Derived only from tenant, retro id, and transition version so a retry of
     * the same transition is a replay.
     */
    public static String idempotencyKey(UUID tenantId, UUID retrocessionId, int transitionVersion) {
        return "retrocession:" + BUSINESS_BOOK + ":" + tenantId + ":" + retrocessionId + ":v"
                + transitionVersion;
    }

    public FsriHandoffConfirmation handoff(FsriRetroHandoff handoff) {
        requireHandoff(handoff);
        String idempotencyKey = idempotencyKey(handoff.tenantId(), handoff.retrocessionId(),
                handoff.transitionVersion());
        String correlationId = "fsri-retro-" + handoff.retrocessionId() + "-v" + handoff.transitionVersion();
        Map<String, Object> body = requestBody(handoff, idempotencyKey, correlationId);

        try {
            return client.post()
                    .uri(handoffPath)
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.APPLICATION_JSON)
                    .header("Idempotency-Key", idempotencyKey)
                    .header("X-Correlation-Id", correlationId)
                    .body(body)
                    .exchange((request, response) -> {
                        int status = response.getStatusCode().value();
                        byte[] payload = response.getBody().readAllBytes();
                        if (status >= 200 && status < 300) {
                            return confirmation(handoff, status, payload, response.getHeaders(), correlationId);
                        }
                        throw translate(status, payload, response.getHeaders(), correlationId);
                    });
        } catch (ResourceAccessException ex) {
            throw unavailable("FS-RI did not respond before the configured timeout", ex, correlationId);
        } catch (DomainException ex) {
            throw ex;
        } catch (RestClientException ex) {
            throw unavailable("FS-RI retro hand-off failed before a response was received", ex, correlationId);
        }
    }

    private void requireHandoff(FsriRetroHandoff handoff) {
        if (handoff == null) {
            throw DomainException.validation("handoff", "must not be null");
        }
        if (handoff.tenantId() == null || handoff.retrocessionId() == null) {
            throw DomainException.validation("handoff", "tenantId and retrocessionId are required");
        }
        if (handoff.contractNumber() == null
                || !handoff.contractNumber().matches("[A-Z0-9][A-Z0-9-]{3,31}")) {
            throw DomainException.validation("fsriContractNumber",
                    "must match an FS-RI contract number (4-32 uppercase alphanumerics or hyphens)");
        }
        if (handoff.retrocessionNumber() == null || handoff.retrocessionNumber().isBlank()) {
            throw DomainException.validation("retrocessionNumber", "must not be blank");
        }
        if (handoff.contractId() == null) {
            throw DomainException.validation("contractId", "must not be null");
        }
        if (handoff.expectedAmount() == null || handoff.expectedAmount().signum() <= 0) {
            throw DomainException.validation("expectedAmount", "must be greater than zero");
        }
        if (handoff.currency() == null || !handoff.currency().matches("[A-Z]{3}")) {
            throw DomainException.validation("currency", "must be a three-letter ISO currency code");
        }
        if (handoff.transitionVersion() < 1) {
            throw DomainException.validation("transitionVersion", "must be one or greater");
        }
    }

    private Map<String, Object> requestBody(FsriRetroHandoff handoff, String idempotencyKey,
                                            String correlationId) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("messageId", UUID.randomUUID().toString());
        body.put("idempotencyKey", idempotencyKey);
        body.put("correlationId", correlationId);
        body.put("tenantId", handoff.tenantId().toString());
        body.put("businessBook", BUSINESS_BOOK);
        body.put("sourceSystem", SOURCE_SYSTEM);
        body.put("targetSystem", TARGET_SYSTEM);
        body.put("schemaVersion", SCHEMA_VERSION);
        body.put("createdAt", Instant.now().truncatedTo(ChronoUnit.MILLIS).toString());

        body.put("contractNumber", handoff.contractNumber());
        body.put("retroCaseRef", handoff.retrocessionNumber());
        body.put("source", Map.of(
                "type", "CONTRACT",
                "id", handoff.contractId().toString(),
                "businessKey", handoff.contractId().toString(),
                "version", handoff.transitionVersion(),
                "book", BUSINESS_BOOK));
        body.put("contractRef", Map.of(
                "type", "CONTRACT",
                "id", handoff.contractId().toString(),
                "businessKey", handoff.contractId().toString()));
        body.put("expectedAmount", money(handoff.expectedAmount(), handoff.currency()));
        if (handoff.cededLoss() != null && handoff.cededLoss().signum() >= 0) {
            body.put("cededLoss", money(handoff.cededLoss(), handoff.currency()));
        }
        body.put("effectiveDate", handoff.effectiveDate().toString());
        body.put("ruleVersion", "v1");
        return body;
    }

    private FsriHandoffConfirmation confirmation(FsriRetroHandoff handoff, int status, byte[] payload,
                                                 HttpHeaders headers, String correlationId) {
        JsonNode node = readTree(payload);
        String handoffId = node == null ? null : text(node, "fsriRetroId", null);
        if (handoffId == null || handoffId.isBlank()) {
            throw new DomainException(HttpStatus.BAD_GATEWAY, "FSRI_INVALID_RESPONSE",
                    "FS-RI accepted the request without returning a retro id",
                    Map.of("upstreamStatus", Integer.toString(status), "correlationId", correlationId));
        }

        Money expected = money(node.get("expectedAmount"));
        Money confirmed = money(node.get("confirmedAmount"));
        Money variance = money(node.get("variance"));
        JsonNode confirmation = node.path("confirmation");
        String confirmationId = text(confirmation, "confirmationId", null);
        Instant handedOffAt = instant(text(node, "receivedAt", null));
        boolean replayed = node.path("duplicate").asBoolean(false)
                || "true".equalsIgnoreCase(headers.getFirst("Idempotent-Replayed"));
        String fsriStatus = text(node, "status", status == 207 ? "PARTIAL" : "POSTED");

        return new FsriHandoffConfirmation(
                handoffId,
                fsriStatus,
                replayed,
                confirmationId,
                expected.amount() != null ? expected.amount() : handoff.expectedAmount(),
                confirmed.amount(),
                variance.amount(),
                expected.currency() != null ? expected.currency() : handoff.currency(),
                handedOffAt,
                correlationId);
    }

    private DomainException translate(int status, byte[] payload, HttpHeaders headers, String correlationId) {
        JsonNode problem = readTree(payload);
        String title = text(problem, "title", "FS-RI retro hand-off failed");
        String detail = text(problem, "detail", "");
        String upstreamCode = text(problem, "code", "EIA-SIM-" + status);
        String upstreamCorrelation = text(problem, "correlationId", correlationId);
        String reasonCode = text(problem == null ? null : problem.path("meta"), "reasonCode", null);

        Map<String, String> details = new LinkedHashMap<>();
        details.put("upstreamStatus", Integer.toString(status));
        details.put("fsriCode", upstreamCode);
        details.put("correlationId", upstreamCorrelation);
        if (reasonCode != null) {
            details.put("reasonCode", reasonCode);
        }
        if (problem != null && problem.hasNonNull("errors") && !problem.get("errors").isEmpty()) {
            details.put("errors", problem.get("errors").toString());
        }
        String retryAfter = headers.getFirst("Retry-After");
        if (retryAfter != null) {
            details.put("retryAfter", retryAfter);
        }
        String message = detail.isBlank() ? title : title + ": " + detail;

        if (status == 409) {
            String existing = firstText(problem == null ? null : problem.path("meta"),
                    "fsriRetroId", "fsriCessionId");
            if (existing != null) {
                details.put("fsriHandoffId", existing);
            }
            return DomainException.fsriConflict(message, details);
        }
        if (status == 429) {
            return new DomainException(HttpStatus.SERVICE_UNAVAILABLE, "FSRI_THROTTLED", message, details);
        }
        if (status == 400) {
            return new DomainException(HttpStatus.BAD_REQUEST, "FSRI_VALIDATION", message, details);
        }
        if (status == 422) {
            return new DomainException(HttpStatus.BAD_REQUEST, "FSRI_REJECTED", message, details);
        }
        if (status == 403) {
            return new DomainException(HttpStatus.BAD_GATEWAY, "FSRI_BOOK_WALL", message, details);
        }
        if (status == 404) {
            return new DomainException(HttpStatus.BAD_GATEWAY, "FSRI_CONTRACT_NOT_FOUND", message, details);
        }
        if (status >= 500) {
            return DomainException.fsriUnavailable(message, details);
        }
        return new DomainException(HttpStatus.BAD_GATEWAY, "FSRI_REJECTED", message, details);
    }

    private DomainException unavailable(String message, Exception cause, String correlationId) {
        Map<String, String> details = new LinkedHashMap<>();
        details.put("cause", cause.getClass().getSimpleName());
        details.put("correlationId", correlationId);
        if (cause.getMessage() != null && !cause.getMessage().isBlank()) {
            details.put("message", cause.getMessage());
        }
        return DomainException.fsriUnavailable(message, details);
    }

    private JsonNode readTree(byte[] payload) {
        if (payload == null || payload.length == 0) {
            return null;
        }
        try {
            return objectMapper.readTree(payload);
        } catch (IOException ex) {
            return null;
        }
    }

    private static Map<String, Object> money(BigDecimal amount, String currency) {
        return Map.of("amount", amount.toPlainString(), "currency", currency);
    }

    private static Money money(JsonNode node) {
        if (node == null || node.isNull() || node.isMissingNode()) {
            return new Money(null, null);
        }
        BigDecimal amount = node.hasNonNull("amount") ? new BigDecimal(node.get("amount").asText()) : null;
        return new Money(amount, text(node, "currency", null));
    }

    private static String text(JsonNode node, String field, String fallback) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return fallback;
        }
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) {
            return fallback;
        }
        String text = value.asText();
        return text == null || text.isBlank() ? fallback : text;
    }

    private static String firstText(JsonNode node, String... fields) {
        for (String field : fields) {
            String value = text(node, field, null);
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    private static Instant instant(String value) {
        if (value == null || value.isBlank()) {
            return Instant.now();
        }
        try {
            return Instant.parse(value);
        } catch (RuntimeException ex) {
            return Instant.now();
        }
    }

    private static int millis(Duration duration) {
        long millis = duration == null ? 0 : duration.toMillis();
        return (int) Math.max(1, Math.min(Integer.MAX_VALUE, millis));
    }

    private record Money(BigDecimal amount, String currency) {}
}
