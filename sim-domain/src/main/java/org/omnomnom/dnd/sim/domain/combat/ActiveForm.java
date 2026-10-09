package org.omnomnom.dnd.sim.domain.combat;

/** A Wild Shape beast form: overrides AC and the attack set until its (temporary) Hit Points are gone. */
public record ActiveForm(int ac, AttackProfile attack) {}
