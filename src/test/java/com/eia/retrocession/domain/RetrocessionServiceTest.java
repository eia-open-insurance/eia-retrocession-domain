package com.eia.retrocession.domain;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RetrocessionServiceTest {

    private static final UUID TENANT = UUID.fromString("11111111-1111-7111-8111-111111111111");
    private static final UUID ID = UUID.fromString("22222222-2222-7222-8222-222222222222");
    private static final UUID CONTRACT = UUID.fromString("33333333-3333-7333-8333-333333333333");
    private static final UUID RETRO_CONTRACT = UUID.fromString("44444444-4444-7444-8444-444444444444");

    @Mock
    private RetrocessionClient client;

    @InjectMocks
    private RetrocessionService service;

    private static CreateRetrocessionCommand command(String premium, String loss, String currency) {
        return new CreateRetrocessionCommand(CONTRACT, RETRO_CONTRACT,
                new BigDecimal(premium), new BigDecimal(loss), currency);
    }

    private static RetrocessionDto dto(RetrocessionStatus status, int version) {
        Instant now = Instant.parse("2026-10-19T12:00:00Z");
        return new RetrocessionDto(ID, TENANT, "ASSUMED", "RET-000000001", CONTRACT, RETRO_CONTRACT, status,
                new BigDecimal("1000.0000"), new BigDecimal("250.0000"), "USD", version, now, now);
    }

    @Test
    void rejectsNegativeCededPremium() {
        assertThatThrownBy(() -> service.create(TENANT, command("-1.0000", "0.0000", "USD")))
                .isInstanceOfSatisfying(DomainException.class, ex -> {
                    assertThat(ex.getCode()).isEqualTo("VALIDATION");
                    assertThat(ex.getDetails()).containsEntry("cededPremium", "must be zero or greater");
                });

        verify(client, never()).create(any(), any());
    }

    @Test
    void rejectsNullCededLoss() {
        assertThatThrownBy(() -> service.create(TENANT,
                new CreateRetrocessionCommand(CONTRACT, RETRO_CONTRACT, BigDecimal.TEN, null, "USD")))
                .isInstanceOfSatisfying(DomainException.class, ex ->
                        assertThat(ex.getDetails()).containsEntry("cededLoss", "must not be null"));
    }

    @Test
    void rejectsInvalidCurrency() {
        assertThatThrownBy(() -> service.create(TENANT, command("1.0000", "0.0000", "EURO")))
                .isInstanceOfSatisfying(DomainException.class, ex ->
                        assertThat(ex.getDetails()).containsEntry("currency",
                                "must be a three-letter currency code"));
    }

    @Test
    void delegatesCreateToExpert() {
        when(client.create(eq(TENANT), any())).thenReturn(dto(RetrocessionStatus.OPEN, 0));

        RetrocessionDto created = service.create(TENANT, command("1000.0000", "250.0000", "USD"));

        assertThat(created.status()).isEqualTo(RetrocessionStatus.OPEN);
        assertThat(created.businessBook()).isEqualTo("ASSUMED");
        verify(client).create(eq(TENANT), any());
    }

    @Test
    void transitionReadsCurrentVersionAndDelegates() {
        when(client.findById(TENANT, ID)).thenReturn(dto(RetrocessionStatus.OPEN, 0));
        when(client.updateStatus(TENANT, ID, RetrocessionStatus.RULE_APPLIED, 0))
                .thenReturn(dto(RetrocessionStatus.RULE_APPLIED, 1));

        RetrocessionDto updated = service.transition(TENANT, ID, RetrocessionStatus.RULE_APPLIED);

        assertThat(updated.status()).isEqualTo(RetrocessionStatus.RULE_APPLIED);
        assertThat(updated.version()).isEqualTo(1);
    }

    @Test
    void transitionRejectsIllegalEdgeWithoutUpdating() {
        when(client.findById(TENANT, ID)).thenReturn(dto(RetrocessionStatus.OPEN, 0));

        assertThatThrownBy(() -> service.transition(TENANT, ID, RetrocessionStatus.HANDED_OFF))
                .isInstanceOfSatisfying(DomainException.class, ex -> {
                    assertThat(ex.getCode()).isEqualTo("INVALID_STATUS_TRANSITION");
                    assertThat(ex.getStatus().value()).isEqualTo(400);
                });

        verify(client, never()).updateStatus(any(), any(), any(), anyInt());
    }

    @Test
    void lifecycleAllowsRejectionRetryAndTerminalStates() {
        assertThat(RetrocessionStatus.HANDOFF_PENDING.canTransitionTo(RetrocessionStatus.REJECTED)).isTrue();
        assertThat(RetrocessionStatus.REJECTED.canTransitionTo(RetrocessionStatus.HANDOFF_PENDING)).isTrue();
        assertThat(RetrocessionStatus.HANDED_OFF.canTransitionTo(RetrocessionStatus.CONFIRMED)).isTrue();
        assertThat(RetrocessionStatus.CONFIRMED.isTerminal()).isTrue();
        assertThat(RetrocessionStatus.CANCELLED.isTerminal()).isTrue();
        assertThat(RetrocessionStatus.CONFIRMED.canTransitionTo(RetrocessionStatus.CANCELLED)).isFalse();
    }
}
