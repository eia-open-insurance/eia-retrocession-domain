package com.eia.retrocession.domain;

import jakarta.validation.constraints.NotNull;

public record TransitionRetrocessionCommand(
        @NotNull RetrocessionStatus targetStatus
) {}
