package org.omnomnom.dnd.sim.domain.opt;

import java.util.List;
import org.omnomnom.dnd.sim.domain.combat.FeatureFactory;
import org.omnomnom.dnd.sim.domain.combat.ResourceSpec;
import org.omnomnom.dnd.sim.domain.combat.Spell;
import org.omnomnom.dnd.sim.domain.combat.SpellcastingSpec;
import org.omnomnom.dnd.sim.domain.content.ArmorInfo;
import org.omnomnom.dnd.sim.domain.content.WeaponInfo;
import org.omnomnom.dnd.sim.domain.core.Ability;

/**
 * A caster's fixed spell and gear package (spell selection is not evolved). Features are factories, so every hero built
 * from the package gets its own feature instances.
 *
 * @param armor null when unarmored
 * @param extraHp Draconic Resilience: +1 per level
 * @param unarmoredAcAbility Draconic Resilience: 10 + Dex + this ability when unarmored; may be null
 * @param shortRestSlots Pact Magic: slots recharge on a short rest (Warlock)
 */
public record CasterPackage(
        Ability spellAbility,
        List<Spell> cantrips,
        List<Spell> spells,
        WeaponInfo weapon,
        ArmorInfo armor,
        boolean shield,
        List<SpellcastingSpec.Slot> slots,
        List<ResourceSpec> resources,
        List<FeatureFactory> features,
        int extraHp,
        Ability unarmoredAcAbility,
        boolean shortRestSlots) {}
