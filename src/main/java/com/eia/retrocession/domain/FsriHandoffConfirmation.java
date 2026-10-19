package com.eia.retrocession.domain;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Normalised acknowledgement of an FS-RI retro hand-off (SPEC 7.18.3 HAND-03,
 * 7.23.5). The FS-RI retro reference is the authoritative external id;
 * {@code fsriStatus} is the FS-RI hand-off result ({@code POSTED} or
 * {@code PARTIAL}).
 */
public record FsriHandoffConfirmation(
        String fsriHandoffId,
        String fsriStatus,
        boolean replayed,
        String confirmationId,
        BigDecimal expectedAmount,
        BigDecimal confirmedAmount,
        BigDecimal variance,
        String currency,
        Instant handedOffAt,
        String correlationId
) {}
