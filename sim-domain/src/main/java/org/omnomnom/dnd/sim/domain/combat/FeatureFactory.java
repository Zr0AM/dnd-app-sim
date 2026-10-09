package org.omnomnom.dnd.sim.domain.combat;

/** Creates a fresh {@link Feature} for one combatant. */
@FunctionalInterface
public interface FeatureFactory {

    Feature create();

    /**
     * A factory that hands every combatant the same instance. Only for features with no mutable state; a stateful
     * feature must build a new instance in {@link #create()}.
     */
    static FeatureFactory shared(Feature stateless) {
        return () -> stateless;
    }
}
