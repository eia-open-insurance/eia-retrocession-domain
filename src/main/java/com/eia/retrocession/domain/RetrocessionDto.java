package com.eia.retrocession.domain;

import com.fasterxml.jackson.annotation.JsonFormat;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record RetrocessionDto(
        UUID id,
        UUID tenantId,
        String businessBook,
        String retrocessionNumber,
        UUID contractId,
        UUID retroContractId,
        RetrocessionStatus status,
        @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal cededPremium,
        @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal cededLoss,
        String currency,
        int version,
        Instant createdAt,
        Instant updatedAt,
        String fsriHandoffId,
        String fsriStatus,
        Instant fsriHandedOffAt,
        String fsriLastError
) {
    public RetrocessionDto(UUID id,
                           UUID tenantId,
                           String businessBook,
                           String retrocessionNumber,
                           UUID contractId,
                           UUID retroContractId,
                           RetrocessionStatus status,
                           BigDecimal cededPremium,
                           BigDecimal cededLoss,
                           String currency,
                           int version,
                           Instant createdAt,
                           Instant updatedAt) {
        this(id, tenantId, businessBook, retrocessionNumber, contractId, retroContractId, status,
                cededPremium, cededLoss, currency, version, createdAt, updatedAt,
                null, null, null, null);
    }
}
