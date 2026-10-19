package com.eia.retrocession.domain;

import java.util.Map;
import java.util.Set;

/**
 * Retro-case lifecycle from SPEC 11.10.3 (retro_case.status).
 *
 * <pre>
 * OPEN -> RULE_APPLIED -> HANDOFF_PENDING -> HANDED_OFF -> CONFIRMED
 * HANDOFF_PENDING / HANDED_OFF -> REJECTED -> HANDOFF_PENDING
 * OPEN / RULE_APPLIED / HANDOFF_PENDING / REJECTED -> CANCELLED
 * </pre>
 */
public enum RetrocessionStatus {
    OPEN,
    RULE_APPLIED,
    HANDOFF_PENDING,
    HANDED_OFF,
    CONFIRMED,
    REJECTED,
    CANCELLED;

    private static final Map<RetrocessionStatus, Set<RetrocessionStatus>> ALLOWED = Map.of(
            OPEN, Set.of(RULE_APPLIED, CANCELLED),
            RULE_APPLIED, Set.of(HANDOFF_PENDING, CANCELLED),
            HANDOFF_PENDING, Set.of(HANDED_OFF, REJECTED, CANCELLED),
            HANDED_OFF, Set.of(CONFIRMED, REJECTED),
            REJECTED, Set.of(HANDOFF_PENDING, CANCELLED),
            CONFIRMED, Set.of(),
            CANCELLED, Set.of()
    );

    public boolean canTransitionTo(RetrocessionStatus target) {
        return ALLOWED.getOrDefault(this, Set.of()).contains(target);
    }

    public boolean isTerminal() {
        return ALLOWED.getOrDefault(this, Set.of()).isEmpty();
    }
}
