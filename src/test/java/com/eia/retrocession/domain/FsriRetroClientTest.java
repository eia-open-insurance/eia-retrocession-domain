package com.eia.retrocession.domain;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Exercises the FS-RI retro client against a local JDK {@link HttpServer} so
 * the exact path, envelope, headers, and error translation are proven without a
 * running simulator.
 */
class FsriRetroClientTest {

    private static final UUID TENANT = UUID.fromString("0192f3a4-0000-7000-8000-000000000001");
    private static final UUID ID = UUID.fromString("0192f3a4-0000-7000-8000-000000031001");
    private static final UUID CONTRACT = UUID.fromString("0192f3a4-0000-7000-8000-000000031002");
    private static final String PATH = "/sim/fsri/v1/retros";
    private static final String CONTRACT_NUMBER = "RET-2026-0001";

    private final ObjectMapper mapper = new ObjectMapper();
    private HttpServer server;
    private ExecutorService executor;
    private FsriRetroClient client;
    private volatile Stub stub;
    private volatile String capturedPath;
    private volatile String capturedIdempotencyKey;
    private volatile String capturedBody;

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(PATH, this::handle);
        executor = Executors.newCachedThreadPool();
        server.setExecutor(executor);
        server.start();
        client = new FsriRetroClient(
                RestClient.builder(),
                mapper,
                "http://127.0.0.1:" + server.getAddress().getPort(),
                PATH,
                Duration.ofMillis(500),
                Duration.ofMillis(300));
        stub = Stub.json(201, successBody(false));
    }

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
        if (executor != null) {
            executor.shutdownNow();
        }
    }

    @Test
    void postsDocumentedRetroPathAndEnvelopeWithDerivedIdempotencyKey() throws Exception {
        FsriHandoffConfirmation confirmation = client.handoff(handoff());

        assertThat(capturedPath).isEqualTo(PATH);
        assertThat(capturedIdempotencyKey)
                .isEqualTo(FsriRetroClient.idempotencyKey(TENANT, ID, 1))
                .isEqualTo("retrocession:ASSUMED:" + TENANT + ":" + ID + ":v1");

        JsonNode body = mapper.readTree(capturedBody);
        assertThat(body.path("messageId").asText()).isNotBlank();
        assertThat(body.path("idempotencyKey").asText()).isEqualTo(capturedIdempotencyKey);
        assertThat(body.path("correlationId").asText()).isEqualTo("fsri-retro-" + ID + "-v1");
        assertThat(body.path("tenantId").asText()).isEqualTo(TENANT.toString());
        assertThat(body.path("businessBook").asText()).isEqualTo("ASSUMED");
        assertThat(body.path("sourceSystem").asText()).isEqualTo("eia-retrocession-domain");
        assertThat(body.path("targetSystem").asText()).isEqualTo("FS-RI");
        assertThat(body.path("schemaVersion").asText()).isEqualTo("1.0.0");
        assertThat(body.path("contractNumber").asText()).isEqualTo(CONTRACT_NUMBER);
        assertThat(body.path("retroCaseRef").asText()).isEqualTo("RET-000000001");
        assertThat(body.path("source").path("type").asText()).isEqualTo("CONTRACT");
        assertThat(body.path("source").path("id").asText()).isEqualTo(CONTRACT.toString());
        assertThat(body.path("source").path("version").asInt()).isEqualTo(1);
        assertThat(body.path("source").path("book").asText()).isEqualTo("ASSUMED");
        assertThat(body.path("contractRef").path("type").asText()).isEqualTo("CONTRACT");
        assertThat(body.path("expectedAmount").path("amount").isTextual()).isTrue();
        assertThat(body.path("expectedAmount").path("amount").asText()).isEqualTo("250000.0000");
        assertThat(body.path("expectedAmount").path("currency").asText()).isEqualTo("USD");
        assertThat(body.path("cededLoss").path("amount").asText()).isEqualTo("250000.0000");
        assertThat(body.path("effectiveDate").asText()).isEqualTo("2026-10-19");

        assertThat(confirmation.fsriHandoffId()).isEqualTo("FSRI-RET-ASSUMED-000001");
        assertThat(confirmation.fsriStatus()).isEqualTo("POSTED");
        assertThat(confirmation.confirmationId()).isEqualTo("FSRI-CNF-ASSUMED-000001");
        assertThat(confirmation.replayed()).isFalse();
        assertThat(confirmation.confirmedAmount()).isEqualByComparingTo("250000.0000");
        assertThat(confirmation.currency()).isEqualTo("USD");
        assertThat(confirmation.handedOffAt()).isEqualTo(Instant.parse("2026-10-19T12:00:00Z"));
    }

    @Test
    void duplicateTwoHundredMapsToIdempotentReplay() {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Idempotent-Replayed", "true");
        stub = Stub.json(200, successBody(true), headers);

        FsriHandoffConfirmation confirmation = client.handoff(handoff());

        assertThat(confirmation.replayed()).isTrue();
        assertThat(confirmation.fsriHandoffId()).isEqualTo("FSRI-RET-ASSUMED-000001");
    }

    @Test
    void partialTwoHundredSevenIsAHandoffConfirmation() {
        stub = Stub.json(207, successBody(false).replace("\"status\": \"POSTED\"", "\"status\": \"PARTIAL\""));

        FsriHandoffConfirmation confirmation = client.handoff(handoff());

        assertThat(confirmation.fsriStatus()).isEqualTo("PARTIAL");
    }

    @Test
    void conflictMapsToStructured409WithExistingFsriReference() {
        stub = Stub.problem(409, """
                {
                  "title": "Idempotency key reused with a different payload",
                  "status": 409,
                  "detail": "The same idempotency key was already processed with different content.",
                  "code": "EIA-SIM-IDEMPOTENCY-CONFLICT",
                  "category": "CONFLICT",
                  "retryable": false,
                  "correlationId": "corr-upstream",
                  "errors": [],
                  "meta": {"reasonCode": "HANDOFF_IMMUTABLE", "fsriRetroId": "FSRI-RET-ASSUMED-000009"}
                }
                """);

        assertThatThrownBy(() -> client.handoff(handoff()))
                .isInstanceOfSatisfying(DomainException.class, ex -> {
                    assertThat(ex.getStatus().value()).isEqualTo(409);
                    assertThat(ex.getCode()).isEqualTo("FSRI_CONFLICT");
                    assertThat(ex.getDetails())
                            .containsEntry("reasonCode", "HANDOFF_IMMUTABLE")
                            .containsEntry("fsriHandoffId", "FSRI-RET-ASSUMED-000009");
                });
    }

    @Test
    void businessRejectionMapsToStructured400() {
        stub = Stub.problem(422, """
                {
                  "title": "FS-RI rejected the retro hand-off",
                  "status": 422,
                  "detail": "FS-RI did not accept RET-000000001.",
                  "code": "EIA-SIM-BUSINESS-REJECT",
                  "category": "BUSINESS_REJECT",
                  "retryable": false,
                  "errors": [{"field": "expectedAmount", "code": "OUTSIDE_TOLERANCE", "message": "amount rejected"}],
                  "meta": {"reasonCode": "FSRI-RETRO-REJECTED"}
                }
                """);

        assertThatThrownBy(() -> client.handoff(handoff()))
                .isInstanceOfSatisfying(DomainException.class, ex -> {
                    assertThat(ex.getStatus().value()).isEqualTo(400);
                    assertThat(ex.getCode()).isEqualTo("FSRI_REJECTED");
                    assertThat(ex.getDetails()).containsEntry("reasonCode", "FSRI-RETRO-REJECTED");
                });
    }

    @Test
    void missingFsriContractMapsToStructured502() {
        stub = Stub.problem(404, """
                {"title": "FS-RI contract not found", "status": 404, "code": "EIA-SIM-NOT-FOUND",
                 "category": "NOT_FOUND", "retryable": false, "errors": []}
                """);

        assertThatThrownBy(() -> client.handoff(handoff()))
                .isInstanceOfSatisfying(DomainException.class, ex -> {
                    assertThat(ex.getStatus().value()).isEqualTo(502);
                    assertThat(ex.getCode()).isEqualTo("FSRI_CONTRACT_NOT_FOUND");
                });
    }

    @Test
    void upstreamFiveHundredMapsTo503() {
        stub = Stub.problem(500, """
                {"title": "Simulator internal error", "status": 500, "code": "EIA-SIM-INTERNAL",
                 "category": "INTERNAL", "retryable": false, "errors": []}
                """);

        assertThatThrownBy(() -> client.handoff(handoff()))
                .isInstanceOfSatisfying(DomainException.class, ex -> {
                    assertThat(ex.getStatus().value()).isEqualTo(503);
                    assertThat(ex.getCode()).isEqualTo("FSRI_UNAVAILABLE");
                });
    }

    @Test
    void readTimeoutMapsTo503() {
        stub = new Stub(201, successBody(false), "application/json", Map.of(), 1_000);

        assertThatThrownBy(() -> client.handoff(handoff()))
                .isInstanceOfSatisfying(DomainException.class, ex -> {
                    assertThat(ex.getStatus().value()).isEqualTo(503);
                    assertThat(ex.getCode()).isEqualTo("FSRI_UNAVAILABLE");
                    assertThat(ex.getDetails().get("cause")).isNotBlank();
                });
    }

    @Test
    void rejectsInvalidContractNumberBeforeCallingFsri() {
        FsriRetroHandoff invalid = new FsriRetroHandoff(TENANT, ID, "RET-000000001", "bad number",
                CONTRACT, new BigDecimal("1000000.0000"), new BigDecimal("250000.0000"),
                new BigDecimal("250000.0000"), "USD", LocalDate.parse("2026-09-30"), 1);

        assertThatThrownBy(() -> client.handoff(invalid))
                .isInstanceOfSatisfying(DomainException.class, ex -> {
                    assertThat(ex.getStatus().value()).isEqualTo(400);
                    assertThat(ex.getDetails()).containsKey("fsriContractNumber");
                });
        assertThat(capturedPath).isNull();
    }

    private void handle(HttpExchange exchange) throws IOException {
        capturedPath = exchange.getRequestURI().getPath();
        capturedIdempotencyKey = exchange.getRequestHeaders().getFirst("Idempotency-Key");
        capturedBody = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);

        Stub current = stub;
        try {
            if (current.delayMillis() > 0) {
                Thread.sleep(current.delayMillis());
            }
            byte[] bytes = current.body().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", current.contentType());
            current.headers().forEach((name, value) -> exchange.getResponseHeaders().add(name, value));
            exchange.sendResponseHeaders(current.status(), bytes.length);
            exchange.getResponseBody().write(bytes);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        } finally {
            exchange.close();
        }
    }

    private static FsriRetroHandoff handoff() {
        return new FsriRetroHandoff(
                TENANT,
                ID,
                "RET-000000001",
                CONTRACT_NUMBER,
                CONTRACT,
                new BigDecimal("1000000.0000"),
                new BigDecimal("250000.0000"),
                new BigDecimal("250000.0000"),
                "USD",
                LocalDate.parse("2026-10-19"),
                1);
    }

    private static String successBody(boolean duplicate) {
        return """
                {
                  "fsriRetroId": "FSRI-RET-ASSUMED-000001",
                  "businessBook": "ASSUMED",
                  "contractNumber": "RET-2026-0001",
                  "caseReference": "RET-000000001",
                  "status": "POSTED",
                  "expectedAmount": {"amount": "250000.0000", "currency": "USD"},
                  "confirmedAmount": {"amount": "250000.0000", "currency": "USD"},
                  "variance": {"amount": "0.0000", "currency": "USD"},
                  "receivedAt": "2026-10-19T12:00:00Z",
                  "confirmation": {
                    "confirmationId": "FSRI-CNF-ASSUMED-000001",
                    "fsriRetroId": "FSRI-RET-ASSUMED-000001",
                    "businessBook": "ASSUMED",
                    "status": "CONFIRMED",
                    "expectedAmount": {"amount": "250000.0000", "currency": "USD"},
                    "confirmedAmount": {"amount": "250000.0000", "currency": "USD"},
                    "variance": {"amount": "0.0000", "currency": "USD"},
                    "confirmedAt": "2026-10-19T12:00:00Z",
                    "errors": []
                  },
                  "duplicate": %s,
                  "errors": []
                }
                """.formatted(duplicate);
    }

    private record Stub(int status, String body, String contentType, Map<String, String> headers,
                        int delayMillis) {
        static Stub json(int status, String body) {
            return new Stub(status, body, "application/json", Map.of(), 0);
        }

        static Stub json(int status, String body, Map<String, String> headers) {
            return new Stub(status, body, "application/json", headers, 0);
        }

        static Stub problem(int status, String body) {
            return new Stub(status, body, "application/problem+json", Map.of(), 0);
        }
    }
}
