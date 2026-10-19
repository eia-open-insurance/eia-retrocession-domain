package com.eia.retrocession.domain;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

@Component
public class RetrocessionClient {
    private final RestClient client;
    private final ObjectMapper objectMapper;

    public RetrocessionClient(RestClient.Builder builder,
                              ObjectMapper objectMapper,
                              @Value("${eia.retrocession.expert-url:http://localhost:8093}") String baseUrl) {
        this.client = builder.baseUrl(baseUrl).build();
        this.objectMapper = objectMapper;
    }

    public List<RetrocessionDto> findAll(UUID tenantId) {
        return client.get()
                .uri(uri -> uri.path("/internal/v1/retrocessions").queryParam("tenantId", tenantId).build())
                .retrieve()
                .onStatus(HttpStatusCode::isError, (request, response) -> translate(response))
                .body(new ParameterizedTypeReference<>() {});
    }

    public RetrocessionDto findById(UUID tenantId, UUID id) {
        return client.get()
                .uri(uri -> uri.path("/internal/v1/retrocessions/{id}")
                        .queryParam("tenantId", tenantId).build(id))
                .retrieve()
                .onStatus(HttpStatusCode::isError, (request, response) -> translate(response))
                .body(RetrocessionDto.class);
    }

    public RetrocessionDto create(UUID tenantId, CreateRetrocessionCommand command) {
        return client.post()
                .uri("/internal/v1/retrocessions")
                .body(new CreateRetrocessionRequest(tenantId, "ASSUMED", command.contractId(),
                        command.retroContractId(), command.cededPremium(), command.cededLoss(),
                        command.currency()))
                .retrieve()
                .onStatus(HttpStatusCode::isError, (request, response) -> translate(response))
                .body(RetrocessionDto.class);
    }

    public RetrocessionDto updateStatus(UUID tenantId, UUID id, RetrocessionStatus status, int version) {
        return client.put()
                .uri(uri -> uri.path("/internal/v1/retrocessions/{id}/status")
                        .queryParam("tenantId", tenantId).build(id))
                .body(new StatusRequest(status.name(), version))
                .retrieve()
                .onStatus(HttpStatusCode::isError, (request, response) -> translate(response))
                .body(RetrocessionDto.class);
    }

    private void translate(ClientHttpResponse response) throws IOException {
        HttpStatus status = HttpStatus.resolve(response.getStatusCode().value());
        ApiError error = readError(response);
        String code = error == null || error.code() == null ? "EXPERT_REJECTED" : error.code();
        String message = error == null || error.message() == null
                ? "Retrocession expert rejected the request"
                : error.message();

        if (status == HttpStatus.NOT_FOUND) {
            throw DomainException.notFound(message);
        }
        if (status == HttpStatus.CONFLICT) {
            throw DomainException.badRequest("CONFLICT", message);
        }
        if (status != null && status.is4xxClientError()) {
            throw new DomainException(HttpStatus.BAD_REQUEST, code, message,
                    error == null ? null : error.details());
        }
        throw DomainException.unavailable("Retrocession expert returned "
                + response.getStatusCode().value());
    }

    private ApiError readError(ClientHttpResponse response) {
        try {
            String body = new String(response.getBody().readAllBytes(), StandardCharsets.UTF_8);
            if (body.isBlank()) {
                return null;
            }
            return objectMapper.readValue(body, ApiError.class);
        } catch (IOException ex) {
            return null;
        }
    }

    record CreateRetrocessionRequest(
            UUID tenantId,
            String businessBook,
            UUID contractId,
            UUID retroContractId,
            BigDecimal cededPremium,
            BigDecimal cededLoss,
            String currency
    ) {}

    record StatusRequest(String status, int version) {}
}
