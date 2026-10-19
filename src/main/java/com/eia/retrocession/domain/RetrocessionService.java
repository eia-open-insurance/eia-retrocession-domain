package com.eia.retrocession.domain;

import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * Assumed-book retrocession business validation and state transitions. The
 * expert persists; this service decides what may be persisted.
 */
@Service
public class RetrocessionService {
    private final RetrocessionClient client;

    public RetrocessionService(RetrocessionClient client) {
        this.client = client;
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
        RetrocessionDto current = client.findById(tenantId, id);
        if (!current.status().canTransitionTo(target)) {
            throw DomainException.badRequest("INVALID_STATUS_TRANSITION",
                    "Retrocession " + id + " cannot move from " + current.status() + " to " + target);
        }
        return client.updateStatus(tenantId, id, target, current.version());
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
