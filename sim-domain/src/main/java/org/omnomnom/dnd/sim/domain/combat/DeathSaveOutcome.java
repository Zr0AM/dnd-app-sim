package org.omnomnom.dnd.sim.domain.combat;

public record DeathSaveOutcome(int d20, boolean success, boolean stabilized, boolean died, boolean revived) {}
