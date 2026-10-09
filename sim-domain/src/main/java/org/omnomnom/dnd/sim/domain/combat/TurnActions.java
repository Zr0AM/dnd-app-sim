package org.omnomnom.dnd.sim.domain.combat;

import java.util.List;
import java.util.OptionalInt;
import org.omnomnom.dnd.sim.domain.combat.event.CombatEvent;
import org.omnomnom.dnd.sim.domain.combat.spell.Spell;
import org.omnomnom.dnd.sim.domain.grid.Cell;

/**
 * What a {@link TurnPolicy} may do on its turn: the {@link TurnApi} for one combatant, enforcing the action economy
 * against that turn's {@link TurnResources}. Anything illegal returns empty (or false) and changes nothing.
 *
 * @param resolvers the engine parts that carry out movement, attacks and spells
 */
record TurnActions(Combatant self, TurnResources resources, Resolvers resolvers) implements TurnApi {

    /** The parts of a fight that carry out what a turn asks for. */
    record Resolvers(FightContext ctx, WeaponAttackResolver weapons, MovementResolver movement, SpellResolver spells) {

        /** Wire the parts together over one fight's shared context. */
        static Resolvers create(FightContext ctx) {
            Rolls rolls = new Rolls(ctx);
            DamageApplier applier = new DamageApplier(ctx, rolls);
            WeaponAttackResolver weapons = new WeaponAttackResolver(ctx, rolls, applier);
            return new Resolvers(ctx, weapons, new MovementResolver(ctx, weapons), new SpellResolver(ctx, rolls, applier));
        }
    }

    /** How one weapon attack is paid for. */
    private enum Funding {
        /** The Attack action itself, which also grants any Extra Attacks. */
        ACTION,
        /** One of the further attacks granted by Extra Attack. */
        EXTRA_ATTACK,
        /** A Haste-style extra action, good for a single weapon attack. */
        EXTRA_ACTION,
        NONE
    }

    @Override
    public List<Combatant> enemies() {
        return resolvers.ctx().roster().consciousOpponents(self);
    }

    @Override
    public List<Combatant> allies() {
        return resolvers.ctx().roster().consciousAllies(self);
    }

    @Override
    public List<Combatant> allAllies() {
        return resolvers.ctx().roster().livingAllies(self);
    }

    @Override
    public boolean moveTo(Cell dest) {
        return resolvers.movement().moveTo(self, dest, resources);
    }

    /**
     * The first attack spends the Attack action and grants the Extra Attack(s); further attacks in the same action draw
     * from attacksRemaining. Once both are spent, a Haste-style extra action can fund one more single weapon attack.
     */
    @Override
    public OptionalInt attack(Combatant target, AttackProfile profile) {
        Funding funding = funding();
        if (!target.isConscious() || funding == Funding.NONE) {
            return OptionalInt.empty();
        }
        OptionalInt dealt = resolvers.weapons().resolve(self, target, profile, WeaponAttackResolver.Source.ACTION);
        if (dealt.isPresent()) {
            pay(funding);
        }
        return dealt;
    }

    private Funding funding() {
        if (resources.action) {
            return Funding.ACTION;
        }
        if (resources.attacksRemaining > 0) {
            return Funding.EXTRA_ATTACK;
        }
        return resources.extraAttackActions > 0 ? Funding.EXTRA_ACTION : Funding.NONE;
    }

    private void pay(Funding funding) {
        switch (funding) {
            case ACTION -> {
                resources.action = false;
                resources.attacksRemaining = self.extraAttacks();
            }
            case EXTRA_ACTION -> {
                resources.extraAttackActions -= 1; // one attack only, no Extra Attack chain
                String source = self.buffSourceFor("haste");
                if (source != null) {
                    resolvers.ctx().log(new CombatEvent.BuffBoost(source, "haste", self.id(), 1));
                }
            }
            case EXTRA_ATTACK -> resources.attacksRemaining -= 1;
            case NONE -> throw new IllegalStateException("an attack that is not funded cannot be paid for");
        }
    }

    @Override
    public OptionalInt castSpell(Spell spell, Combatant target, Integer slotLevel, boolean quickened) {
        return resolvers.spells().cast(self, spell, target, slotLevel, resources, quickened);
    }

    /** Lay on Hands: a Bonus Action that heals {@code target} from the 'lay-on-hands' pool. */
    @Override
    public OptionalInt layOnHands(Combatant target) {
        if (!resources.bonus) {
            return OptionalInt.empty();
        }
        int pool = self.resourceCount(ResourceIds.LAY_ON_HANDS);
        if (pool <= 0 || !target.isAlive()) {
            return OptionalInt.empty();
        }
        int missing = Math.max(1, target.maxHp() - target.hp());
        int draw = Math.min(pool, missing);
        int healed = target.heal(draw);
        self.spendResource(ResourceIds.LAY_ON_HANDS, draw);
        resources.bonus = false;
        resolvers.ctx().log(new CombatEvent.Heal(self.id(), target.id(), healed));
        return OptionalInt.of(healed);
    }

    /**
     * Place Hunter's Mark (Ranger): a Bonus Action that marks an enemy and starts concentration, spending one of the
     * ranger's free uses (Favored Enemy). The Hunter's Mark feature then adds its damage to hits on the target.
     */
    @Override
    public boolean markTarget(Combatant target) {
        if (!resources.bonus || target.side() == self.side() || self.resourceCount(ResourceIds.HUNTERS_MARK) <= 0) {
            return false;
        }
        self.spendResource(ResourceIds.HUNTERS_MARK, 1);
        self.setMarkedTarget(target.id());
        self.setConcentratingOn(ResourceIds.HUNTERS_MARK);
        resources.bonus = false;
        resolvers.ctx().log(new CombatEvent.Marked(self.id(), target.id()));
        return true;
    }
}
