package com.eia.retrocession.domain;

import org.springframework.http.HttpStatus;

import java.util.Map;

/**
 * API-visible failure carrying the structured error code and HTTP status.
 */
public class DomainException extends RuntimeException {
    private final HttpStatus status;
    private final String code;
    private final Map<String, String> details;

    public DomainException(HttpStatus status, String code, String message, Map<String, String> details) {
        super(message);
        this.status = status;
        this.code = code;
        this.details = details == null ? Map.of() : Map.copyOf(details);
    }

    public static DomainException tenantRequired() {
        return new DomainException(HttpStatus.BAD_REQUEST, "TENANT_REQUIRED",
                "X-EIA-Tenant header is required", Map.of());
    }

    public static DomainException tenantInvalid() {
        return new DomainException(HttpStatus.BAD_REQUEST, "TENANT_INVALID",
                "X-EIA-Tenant header must be a UUID", Map.of());
    }

    public static DomainException bookRequired() {
        return new DomainException(HttpStatus.BAD_REQUEST, "BOOK_REQUIRED",
                "X-EIA-Book header is required", Map.of());
    }

    public static DomainException bookViolation() {
        return new DomainException(HttpStatus.BAD_REQUEST, "BOOK_SCOPE_VIOLATION",
                "Retrocession is an ASSUMED-only vertical; X-EIA-Book must be ASSUMED", Map.of());
    }

    public static DomainException validation(String field, String message) {
        return new DomainException(HttpStatus.BAD_REQUEST, "VALIDATION", "Request validation failed",
                Map.of(field, message));
    }

    public static DomainException badRequest(String code, String message) {
        return new DomainException(HttpStatus.BAD_REQUEST, code, message, Map.of());
    }

    public static DomainException notFound(String message) {
        return new DomainException(HttpStatus.NOT_FOUND, "NOT_FOUND", message, Map.of());
    }

    public static DomainException unavailable(String message) {
        return new DomainException(HttpStatus.SERVICE_UNAVAILABLE, "EXPERT_UNAVAILABLE", message, Map.of());
    }

    public HttpStatus getStatus() {
        return status;
    }

    public String getCode() {
        return code;
    }

    public Map<String, String> getDetails() {
        return details;
    }
}
