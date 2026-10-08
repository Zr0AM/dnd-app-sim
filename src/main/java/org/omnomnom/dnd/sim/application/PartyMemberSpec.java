package org.omnomnom.dnd.sim.application;

import org.omnomnom.dnd.sim.domain.content.Role;

/** One member of the party in an encounter request. */
public sealed interface PartyMemberSpec {

    /** The caller-chosen id, or null for the default. */
    String id();

    /** A build compiled from a (partial) genome. */
    record Build(String id, GenomeInput genome) implements PartyMemberSpec {}

    /** A frozen reference-party filler for a role. */
    record Filler(String id, Role role) implements PartyMemberSpec {}
}
