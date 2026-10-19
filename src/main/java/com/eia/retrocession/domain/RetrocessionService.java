package com.eia.retrocession.domain;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Assumed-book retrocession business validation, FS-RI hand-off orchestration,
 * and state transitions. The expert persists; this service decides what may be
 * persisted.
 *
 * <p>The hand-off is intentionally not a local-only status write: a request to
 * move to {@link RetrocessionStatus#HANDOFF_PENDING} calls FS-RI first. Only a
 * 2xx acknowledgement is persisted, and it is persisted as {@code HANDED_OFF}
 * with the FS-RI reference, status, and timestamp in the same optimistic write.
 * A failure leaves the lifecycle status unchanged (retryable) and records the
 * failure metadata.</p>
 */
@Service
public class RetrocessionService {
    private final RetrocessionClient client;
    private final FsriRetroClient fsriClient;

    @Autowired
    public RetrocessionService(RetrocessionClient client, FsriRetroClient fsriClient) {
        this.client = client;
        this.fsriClient = fsriClient;
    }

    public RetrocessionService(RetrocessionClient client) {
        this(client, null);
    }

    public List<RetrocessionDto> list(UUID tenantId) {
        return client.findAll(tenantId);
    }

    public RetrocessionDto get(UUID tenantId, UUID id) {
        return client.findById(tenantId, id);
    }

    public RetrocessionDto create(UUID tenantId, CreateRetrocessionCommand command) {
        requireNonNegative("cededPremium", command.cededPremium());
        requireNonNegative("cededLoss", command.cededLoss());
        requireCurrency(command.currency());
        return client.create(tenantId, command);
    }

    public RetrocessionDto transition(UUID tenantId, UUID id, RetrocessionStatus target) {
        return transition(tenantId, id, target, null);
    }

    public RetrocessionDto transition(UUID tenantId, UUID id, RetrocessionStatus target,
                                      String fsriContractNumber) {
        RetrocessionDto current = client.findById(tenantId, id);
        if (!current.status().canTransitionTo(target)) {
            throw DomainException.badRequest("INVALID_STATUS_TRANSITION",
                    "Retrocession " + id + " cannot move from " + current.status() + " to " + target);
        }
        if (target == RetrocessionStatus.HANDED_OFF) {
            throw DomainException.badRequest("HANDOFF_REQUIRED",
                    "Retrocession " + id + " is handed off only after an FS-RI acknowledgement");
        }
        if (target == RetrocessionStatus.HANDOFF_PENDING) {
            return handOff(tenantId, current, fsriContractNumber);
        }
        return client.updateStatus(tenantId, id, target, current.version());
    }

    private RetrocessionDto handOff(UUID tenantId, RetrocessionDto current, String requestedContractNumber) {
        if (fsriClient == null) {
            throw new DomainException(HttpStatus.SERVICE_UNAVAILABLE, "FSRI_UNAVAILABLE",
                    "FS-RI hand-off is not configured for this service instance", Map.of());
        }
        int transitionVersion = current.version() + 1;
        String contractNumber = resolveContractNumber(current, requestedContractNumber);
        BigDecimal expectedAmount = expectedAmount(current);
        LocalDate effectiveDate = current.createdAt() == null
                ? LocalDate.now(ZoneOffset.UTC)
                : current.createdAt().atZone(ZoneOffset.UTC).toLocalDate();

        FsriRetroHandoff handoff = new FsriRetroHandoff(
                tenantId,
                current.id(),
                current.retrocessionNumber(),
                contractNumber,
                current.contractId(),
                current.cededPremium(),
                current.cededLoss(),
                expectedAmount,
                current.currency(),
                effectiveDate,
                transitionVersion);

        FsriHandoffConfirmation confirmation;
        try {
            confirmation = fsriClient.handoff(handoff);
        } catch (DomainException ex) {
            recordHandoffFailure(tenantId, current, ex);
            throw ex;
        }

        try {
            return client.recordHandoff(tenantId, current.id(), new RetrocessionClient.HandoffRecord(
                    current.version(),
                    confirmation.fsriHandoffId(),
                    confirmation.fsriStatus(),
                    confirmation.handedOffAt()));
        } catch (DomainException ex) {
            Map<String, String> details = new LinkedHashMap<>();
            if (ex.getDetails() != null) {
                details.putAll(ex.getDetails());
            }
            if (confirmation.fsriHandoffId() != null) {
                details.put("fsriHandoffId", confirmation.fsriHandoffId());
            }
            throw new DomainException(HttpStatus.BAD_GATEWAY, "FSRI_HANDOFF_NOT_RECORDED",
                    "FS-RI accepted the hand-off but the retrocession was not updated; retry to reconcile",
                    details);
        }
    }

    private void recordHandoffFailure(UUID tenantId, RetrocessionDto current, DomainException failure) {
        try {
            client.recordHandoffFailure(tenantId, current.id(), new RetrocessionClient.HandoffFailure(
                    current.version(), truncate(failure.getCode() + ": " + failure.getMessage())));
        } catch (RuntimeException ignored) {
            // Best effort only: the FS-RI failure is the canonical response.
        }
    }

    private static String resolveContractNumber(RetrocessionDto current, String requested) {
        String contractNumber = requested == null || requested.isBlank()
                ? current.retrocessionNumber()
                : requested.trim();
        if (contractNumber == null || !contractNumber.matches("[A-Z0-9][A-Z0-9-]{3,31}")) {
            throw DomainException.validation("fsriContractNumber",
                    "must match an FS-RI contract number (4-32 uppercase alphanumerics or hyphens)");
        }
        return contractNumber;
    }

    private static BigDecimal expectedAmount(RetrocessionDto current) {
        BigDecimal amount = current.cededLoss() != null && current.cededLoss().signum() > 0
                ? current.cededLoss()
                : current.cededPremium();
        if (amount == null || amount.signum() <= 0) {
            throw DomainException.validation("expectedAmount",
                    "must be greater than zero for an FS-RI hand-off");
        }
        return amount;
    }

    private static String truncate(String value) {
        if (value == null) {
            return "FS-RI hand-off failed";
        }
        return value.length() <= 1000 ? value : value.substring(0, 1000);
    }

    private static void requireNonNegative(String field, BigDecimal amount) {
        if (amount == null) {
            throw DomainException.validation(field, "must not be null");
        }
        if (amount.signum() < 0) {
            throw DomainException.validation(field, "must be zero or greater");
        }
    }

    private static void requireCurrency(String currency) {
        if (currency == null || !currency.matches("[A-Za-z]{3}")) {
            throw DomainException.validation("currency", "must be a three-letter currency code");
        }
    }
}
