package org.omnomnom.dnd.sim.domain.combat;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.IntUnaryOperator;
import org.omnomnom.dnd.sim.domain.ai.TacticalPolicy;
import org.omnomnom.dnd.sim.domain.combat.event.CombatEvent;
import org.omnomnom.dnd.sim.domain.combat.event.EventLog;
import org.omnomnom.dnd.sim.domain.combat.spell.DamageScaling;
import org.omnomnom.dnd.sim.domain.combat.spell.Spell;
import org.omnomnom.dnd.sim.domain.combat.spell.SpellKind;
import org.omnomnom.dnd.sim.domain.combat.spell.SpellcastingSpec;
import org.omnomnom.dnd.sim.domain.core.Ability;
import org.omnomnom.dnd.sim.domain.core.AbilityScores;
import org.omnomnom.dnd.sim.domain.core.Condition;
import org.omnomnom.dnd.sim.domain.core.DamageResponse;
import org.omnomnom.dnd.sim.domain.core.DamageType;
import org.omnomnom.dnd.sim.domain.core.Side;
import org.omnomnom.dnd.sim.domain.dice.Dice;
import org.omnomnom.dnd.sim.domain.grid.Cell;
import org.omnomnom.dnd.sim.domain.grid.Grid;
import org.omnomnom.dnd.sim.domain.grid.GridMath;
import org.omnomnom.dnd.sim.domain.rng.LabeledRandom;
import org.omnomnom.dnd.sim.testsupport.RecipeFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Builds the shared reference scenarios ({@code reference/scenarios.json}) and runs them through the Java engine.
 * Mirrors {@code tools/reference/gen-encounters.mts}, which runs the same scenarios through the original TypeScript
 * engine; the plan intent grammar is documented there. Keep the two in lockstep.
 */
final class ScenarioRunner {

    static final ObjectMapper MAPPER = new ObjectMapper();

    private final Map<String, Spell> spells = new HashMap<>();

    /** The outcome of one scenario in the same JSON shape the TypeScript generator writes. */
    record Outcome(String name, int rounds, String winner, List<ObjectNode> events, List<ObjectNode> finalStates) {}

    static JsonNode load(String resource) throws IOException {
        try (InputStream in = ScenarioRunner.class.getResourceAsStream(resource)) {
            return MAPPER.readTree(in);
        }
    }

    private final RecipeFactory recipes;

    ScenarioRunner(JsonNode spellDefs) {
        this(spellDefs, null);
    }

    /** With a recipe factory, combatants may be given as build recipes instead of explicit stat blocks. */
    ScenarioRunner(JsonNode spellDefs, RecipeFactory recipes) {
        this.recipes = recipes;
        spellDefs.properties().forEach(e -> spells.put(e.getKey(), makeSpell(e.getKey(), e.getValue())));
    }

    // ---- spells --------------------------------------------------------------------------------

    private static Dice dice(JsonNode a) {
        return Dice.of(a.get(0).asInt(), a.get(1).asInt(), a.get(2).asInt());
    }

    private static DamageScaling scaling(JsonNode s) {
        int count = s.get("count").asInt();
        int sides = s.get("sides").asInt();
        return switch (s.get("type").asString()) {
            case "cantrip" -> Spell.cantripDice(count, sides);
            case "upcast" -> Spell.upcastDice(s.get("baseLevel").asInt(), count, sides, s.get("perUpcast").asInt());
            case "fixed" -> (slot, level) -> Dice.of(count, sides, s.path("bonus").asInt(0));
            default -> throw new IllegalArgumentException("scaling " + s);
        };
    }

    private static Integer optInt(JsonNode n, String field) {
        return n.has(field) ? n.get(field).asInt() : null;
    }

    private static Spell makeSpell(String id, JsonNode s) {
        JsonNode k = s.get("kind");
        SpellKind kind = switch (k.get("type").asString()) {
            case "attack-damage" -> {
                IntUnaryOperator beams = null;
                if (k.has("beamsByLevel")) {
                    JsonNode table = k.get("beamsByLevel");
                    beams = level -> {
                        int result = 0;
                        for (JsonNode t : table) {
                            if (t.get(0).asInt() <= level) {
                                result = t.get(1).asInt();
                            }
                        }
                        return result;
                    };
                }
                yield new SpellKind.AttackDamage(
                        scaling(k.get("damage")),
                        DamageType.fromCode(k.get("damageType").asString()),
                        k.path("rays").asInt(1),
                        k.path("raysPerUpcast").asInt(0),
                        beams,
                        k.path("addSpellMod").asBoolean(false));
            }
            case "save-damage" -> new SpellKind.SaveDamage(
                    Ability.fromCode(k.get("save").asString()),
                    scaling(k.get("damage")),
                    DamageType.fromCode(k.get("damageType").asString()),
                    SpellKind.OnSuccess.valueOf(k.get("onSuccess").asString().toUpperCase(java.util.Locale.ROOT)),
                    optInt(k, "aoeRadiusFt"),
                    k.path("selfOrigin").asBoolean(false));
            case "heal" -> new SpellKind.Heal(scaling(k.get("dice")), k.get("addSpellMod").asBoolean());
            case "control" -> new SpellKind.Control(
                    Ability.fromCode(k.get("save").asString()),
                    Condition.fromCode(k.get("condition").asString()),
                    k.get("rounds").asInt(),
                    k.get("repeatSaveEndsEffect").asBoolean(),
                    optInt(k, "aoeRadiusFt"),
                    null);
            case "buff" -> new SpellKind.Buff(
                    k.get("buffId").asString(),
                    k.get("maxTargets").asInt(),
                    k.get("rounds").asInt(),
                    k.has("attackBonusDice") ? dice(k.get("attackBonusDice")) : null,
                    k.has("saveBonusDice") ? dice(k.get("saveBonusDice")) : null,
                    k.path("acBonus").asInt(0),
                    k.path("extraAttackAction").asBoolean(false));
            default -> throw new IllegalArgumentException("kind " + k);
        };
        return new Spell(
                id,
                s.get("name").asString(),
                s.get("level").asInt(),
                s.get("action").asString().equals("bonus") ? Spell.CastingTime.BONUS : Spell.CastingTime.ACTION,
                s.get("rangeFt").asInt(),
                s.get("concentration").asBoolean(),
                kind);
    }

    // ---- features (each combatant gets its own instance, as in the TypeScript generator) -------

    private static final class Berserk implements Feature {
        private boolean riderUsed;
        private boolean effectUsed;

        @Override
        public String id() {
            return "berserk";
        }

        @Override
        public void onTurnStart(Combatant self) {
            riderUsed = false;
            effectUsed = false;
        }

        @Override
        public List<ExtraDamage> onHit(OnHitContext ctx) {
            if (riderUsed) {
                return List.of();
            }
            riderUsed = true;
            return List.of(new ExtraDamage(Dice.of(1, 6), DamageType.NECROTIC));
        }

        @Override
        public java.util.Optional<HitEffect> onHitEffect(OnHitContext ctx) {
            if (effectUsed) {
                return java.util.Optional.empty();
            }
            effectUsed = true;
            return java.util.Optional.of(new HitEffect(Ability.CON, 11, Condition.STUNNED, 1));
        }

        @Override
        public void onKill(Combatant self, Combatant victim) {
            self.grantTempHp(5);
        }

        @Override
        public boolean resistsDamage(Combatant self, DamageType type) {
            return type == DamageType.BLUDGEONING || type == DamageType.PIERCING || type == DamageType.SLASHING;
        }
    }

    private static final class Reckless implements Feature {
        @Override
        public String id() {
            return "reckless";
        }

        @Override
        public OutgoingAttackMods outgoingAttack(Combatant self, Combatant target, AttackProfile weapon) {
            return weapon.kind() == AttackKind.MELEE ? new OutgoingAttackMods(true, false, 0) : OutgoingAttackMods.NONE;
        }

        @Override
        public boolean grantsAttackersAdvantage(Combatant self) {
            return true;
        }
    }

    private static final class Flurry implements Feature {
        @Override
        public String id() {
            return "flurry";
        }

        @Override
        public int bonusAttackActions(Combatant self) {
            return 1;
        }
    }

    private static final class HuntersMark implements Feature {
        @Override
        public String id() {
            return "hunters-mark";
        }

        @Override
        public List<ExtraDamage> onHit(OnHitContext ctx) {
            return ctx.target().id().equals(ctx.self().markedTarget())
                    ? List.of(new ExtraDamage(Dice.of(1, 6), DamageType.FORCE))
                    : List.of();
        }
    }

    private static FeatureFactory featureFactory(String id) {
        return switch (id) {
            case "berserk" -> Berserk::new;
            case "reckless" -> Reckless::new;
            case "flurry" -> Flurry::new;
            case "hunters-mark" -> HuntersMark::new;
            case "aura-of-protection" -> () -> () -> "aura-of-protection";
            default -> throw new IllegalArgumentException("feature " + id);
        };
    }

    // ---- combatants and grid -------------------------------------------------------------------

    private Combatant makeCombatant(JsonNode c) {
        JsonNode ab = c.get("abilities");
        CombatantSpec.Builder b = CombatantSpec.builder(
                        c.get("id").asString(),
                        c.get("name").asString(),
                        c.get("side").asString().equals("party") ? Side.PARTY : Side.ENEMY,
                        c.get("level").asInt(),
                        AbilityScores.of(ab.get(0).asInt(), ab.get(1).asInt(), ab.get(2).asInt(), ab.get(3).asInt(),
                                ab.get(4).asInt(), ab.get(5).asInt()),
                        c.get("ac").asInt(),
                        c.get("maxHp").asInt())
                .position(new Cell(c.get("position").get(0).asInt(), c.get("position").get(1).asInt()));

        List<AttackProfile> attacks = new ArrayList<>();
        for (JsonNode a : c.get("attacks")) {
            AttackProfile.Builder ab2 = AttackProfile.builder(
                    a.get("name").asString(),
                    a.get("kind").asString().equals("melee") ? AttackKind.MELEE : AttackKind.RANGED,
                    a.get("attackBonus").asInt(),
                    dice(a.get("damage")),
                    DamageType.fromCode(a.get("damageType").asString()));
            if (a.has("reachFt")) ab2.reachFt(a.get("reachFt").asInt());
            if (a.has("rangeFt")) ab2.rangeFt(a.get("rangeFt").asInt());
            if (a.has("rangeLongFt")) ab2.rangeLongFt(a.get("rangeLongFt").asInt());
            if (a.has("critRange")) ab2.critRange(a.get("critRange").asInt());
            if (a.has("finesse")) ab2.finesse(a.get("finesse").asBoolean());
            if (a.has("extraDamage")) {
                List<ExtraDamage> extras = new ArrayList<>();
                for (JsonNode e : a.get("extraDamage")) {
                    extras.add(new ExtraDamage(dice(e.get("damage")), DamageType.fromCode(e.get("type").asString())));
                }
                ab2.extraDamage(extras);
            }
            attacks.add(ab2.build());
        }
        b.attacks(attacks);

        List<FeatureFactory> features = new ArrayList<>();
        for (JsonNode f : c.path("features")) {
            features.add(featureFactory(f.asString()));
        }
        b.features(features);
        b.extraAttacks(c.path("extraAttacks").asInt(0));
        b.legendaryActions(c.path("legendaryActions").asInt(0));
        if (c.has("speedFt")) b.speedFt(c.get("speedFt").asInt());

        if (c.has("saveProficiencies")) {
            Set<Ability> prof = EnumSet.noneOf(Ability.class);
            c.get("saveProficiencies").forEach(p -> prof.add(Ability.fromCode(p.asString())));
            b.saveProficiencies(prof);
        }
        if (c.has("saveBonuses")) {
            Map<Ability, Integer> sb = new EnumMap<>(Ability.class);
            c.get("saveBonuses").properties().forEach(e -> sb.put(Ability.fromCode(e.getKey()), e.getValue().asInt()));
            b.saveBonuses(sb);
        }
        if (c.has("damageResponses")) {
            Map<DamageType, DamageResponse> dr = new EnumMap<>(DamageType.class);
            c.get("damageResponses").properties().forEach(e -> dr.put(
                    DamageType.fromCode(e.getKey()),
                    DamageResponse.valueOf(e.getValue().asString().toUpperCase(java.util.Locale.ROOT))));
            b.damageResponses(dr);
        }
        if (c.has("resources")) {
            List<ResourceSpec> rs = new ArrayList<>();
            c.get("resources").forEach(r -> rs.add(ResourceSpec.longRest(r.get("id").asString(), r.get("max").asInt())));
            b.resources(rs);
        }
        if (c.has("spellcasting")) {
            JsonNode sc = c.get("spellcasting");
            List<SpellcastingSpec.Slot> slots = new ArrayList<>();
            sc.get("slots").forEach(s -> slots.add(new SpellcastingSpec.Slot(s.get(0).asInt(), s.get(1).asInt())));
            List<Spell> cantrips = new ArrayList<>();
            sc.get("cantrips").forEach(id -> cantrips.add(spells.get(id.asString())));
            List<Spell> known = new ArrayList<>();
            sc.get("spells").forEach(id -> known.add(spells.get(id.asString())));
            b.spellcasting(new SpellcastingSpec(
                    Ability.fromCode(sc.get("ability").asString()), slots, cantrips, known, sc.path("shortRestSlots").asBoolean(false)));
        }
        Combatant combatant = new Combatant(b.build());
        if (c.has("startHp")) {
            combatant.setHp(c.get("startHp").asInt());
        }
        c.path("conditions").forEach(cond -> combatant.addCondition(Condition.fromCode(cond.asString())));
        return combatant;
    }

    private static Grid makeGrid(JsonNode g) {
        Grid.Builder b = Grid.builder(g.get("width").asInt(), g.get("height").asInt(), g.path("cellFt").asInt(5));
        g.path("walls").forEach(w -> b.wall(new Cell(w.get(0).asInt(), w.get(1).asInt())));
        g.path("difficult").forEach(w -> b.difficult(new Cell(w.get(0).asInt(), w.get(1).asInt())));
        return b.build();
    }

    // ---- the plan interpreter (mirrors runIntent in gen-encounters.mts) -----------------------

    private static int dist(Combatant a, Combatant b) {
        return GridMath.distanceFt(a.position(), b.position());
    }

    /** First element with the smallest key (JavaScript's strict-less-than scan), or null. */
    private static Combatant minBy(List<Combatant> list, java.util.function.ToIntFunction<Combatant> key) {
        Combatant best = null;
        long bestKey = Long.MAX_VALUE;
        for (Combatant c : list) {
            int k = key.applyAsInt(c);
            if (k < bestKey) {
                bestKey = k;
                best = c;
            }
        }
        return best;
    }

    private static Combatant pick(String rule, TurnApi api) {
        Combatant self = api.self();
        return switch (rule) {
            case "nearest" -> minBy(api.enemies(), c -> dist(self, c));
            case "farthest" -> minBy(api.enemies(), c -> -dist(self, c));
            case "strongest" -> minBy(api.enemies(), c -> -c.maxHp());
            case "nearest-ally" -> minBy(api.allies(), c -> dist(self, c));
            case "weakest-ally" -> {
                Combatant best = null;
                for (Combatant c : api.allAllies()) {
                    if (c.hp() >= c.maxHp()) {
                        continue;
                    }
                    if (best == null || c.hp() * best.maxHp() < best.hp() * c.maxHp()) {
                        best = c;
                    }
                }
                yield best;
            }
            default -> throw new IllegalArgumentException("rule " + rule);
        };
    }

    private void runIntent(String intent, TurnApi api) {
        String[] p = intent.split(":");
        Combatant self = api.self();
        switch (p[0]) {
            case "approach" -> {
                Combatant t = pick("nearest", api);
                if (t == null) return;
                int rangeFt = Integer.parseInt(p[1]);
                int d = dist(self, t);
                if (d <= rangeFt) return;
                int need = (int) Math.ceil((d - rangeFt) / 5.0);
                int can = api.resources().movementFt() / 5;
                int steps = Math.min(need, can);
                if (steps <= 0) return;
                int x = self.position().x();
                int y = self.position().y();
                for (int i = 0; i < steps; i++) {
                    x += Integer.signum(t.position().x() - x);
                    y += Integer.signum(t.position().y() - y);
                }
                api.moveTo(new Cell(x, y));
            }
            case "retreat" -> {
                Combatant t = pick("nearest", api);
                if (t == null) return;
                int n = Integer.parseInt(p[1]);
                int dx = Integer.signum(self.position().x() - t.position().x());
                int dy = Integer.signum(self.position().y() - t.position().y());
                api.moveTo(new Cell(self.position().x() + dx * n, self.position().y() + dy * n));
            }
            case "attack" -> {
                AttackProfile weapon = self.attacks().get(Integer.parseInt(p[1]));
                int max = Integer.parseInt(p[2]);
                for (int i = 0; i < max; i++) {
                    Combatant t = pick("nearest", api);
                    if (t == null) break;
                    if (api.attack(t, weapon).isEmpty()) break;
                }
            }
            case "cast" -> {
                Combatant t = pick(p[3], api);
                if (t == null) return;
                Integer slot = p[2].equals("-") ? null : Integer.valueOf(p[2]);
                api.castSpell(spells.get(p[1]), t, slot, p[4].equals("q"));
            }
            case "lay" -> {
                Combatant t = pick("weakest-ally", api);
                if (t != null) api.layOnHands(t);
            }
            case "mark" -> {
                Combatant t = pick("nearest", api);
                if (t != null) api.markTarget(t);
            }
            case "policy" -> TacticalPolicy.DEFAULT.act(api);
            default -> throw new IllegalArgumentException("intent " + p[0]);
        }
    }

    // ---- run -----------------------------------------------------------------------------------

    Outcome run(JsonNode sc) {
        List<Combatant> combatants = new ArrayList<>();
        sc.get("combatants").forEach(c -> combatants.add(RecipeFactory.isRecipe(c) ? recipes.make(c) : makeCombatant(c)));
        Map<String, List<String>> plans = new LinkedHashMap<>();
        sc.get("plans").properties().forEach(e -> {
            List<String> plan = new ArrayList<>();
            e.getValue().forEach(i -> plan.add(i.asString()));
            plans.put(e.getKey(), plan);
        });
        EventLog log = new EventLog();
        Encounter e = Encounter.builder(makeGrid(sc.get("grid")), combatants, new LabeledRandom(sc.get("seed").asLong()))
                .policyFor(c -> api -> {
                    for (String intent : plans.getOrDefault(c.id(), List.of())) {
                        runIntent(intent, api);
                    }
                })
                .sink(log)
                .build();
        Encounter.RunResult r = e.run(sc.get("roundCap").asInt());

        List<ObjectNode> events = new ArrayList<>();
        for (CombatEvent ev : log.events()) {
            events.add(EventJson.toJson(MAPPER, ev));
        }
        List<ObjectNode> finals = new ArrayList<>();
        for (Combatant c : combatants) {
            ObjectNode n = MAPPER.createObjectNode();
            n.put("id", c.id());
            n.put("hp", c.hp());
            n.put("tempHp", c.tempHp());
            n.put("dead", c.dead());
            n.put("stable", c.stable());
            n.put("x", c.position().x());
            n.put("y", c.position().y());
            finals.add(n);
        }
        return new Outcome(sc.get("name").asString(), r.rounds(), r.winner() == null ? null : r.winner().code(), events, finals);
    }
}
