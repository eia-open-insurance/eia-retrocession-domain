package com.eia.retrocession.domain;

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
        BigDecimal cededPremium,
        BigDecimal cededLoss,
        String currency,
        int version,
        Instant createdAt,
        Instant updatedAt
) {}
