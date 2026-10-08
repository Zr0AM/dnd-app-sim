# Engine inventory and port hazards

Source: `Zr0AM/dnd-app` `sim/`, commit `8db9df32179604057009203c9effa5bc91c9dd6f`. Sizes are source bytes.
**Read** means the file was read in full for this review; **Not yet read** means only its size, imports
and mentions elsewhere were seen, and it must be read before its port PR.

## Port order and target packages

Dependencies run `rng, dice, core, grid` -> `combat` -> `content` -> `scenario` -> `opt`, with `ai`
depending on `combat` and being injected into encounters. Port bottom-up; each step lands with its
tests.

| # | TypeScript (`sim/src/`) | Bytes | Status | Java target (`org.omnomnom.dnd.sim.domain.*`) |
| --- | --- | --- | --- | --- |
| 1 | `rng/rng.ts` | 5130 | Read | `rng` |
| 1 | `dice/dice.ts` | 3495 | Read | `dice` |
| 1 | `core/types.ts` | 2242 | Read | `core` (enums, `abilityModifier`, `proficiencyBonus`) |
| 1 | `grid/grid.ts` | 4749 | Read | `grid` |
| 2 | `combat/actor.ts` (`Combatant`) | 22890 | Read | `combat` |
| 2 | `combat/attack.ts` | 4208 | Read | `combat` |
| 2 | `combat/damage.ts` | 1737 | Read | `combat` |
| 2 | `combat/conditions.ts` | 5312 | Read | `combat` |
| 2 | `combat/spell.ts` | 5576 | Read | `combat` (sealed `SpellKind`) |
| 3 | `combat/feature.ts` | 3299 | Read | `combat` (`Feature` interface) |
| 3 | `combat/encounter.ts` | 36118 | Read | `combat` (`Encounter`, `CombatEvent`) |
| 3 | `ai/policy.ts` | 17865 | First 180 lines read | `ai` |
| 4 | `content/ids.ts` | 1654 | Not yet read | `content` |
| 4 | `content/monster.ts` | 8519 | Read | `content` |
| 4 | `content/multiattack.ts` | 2499 | Not yet read | `content` |
| 4 | `content/character.ts` | 9947 | Not yet read | `content` |
| 4 | `content/caster.ts` | 4426 | Not yet read | `content` |
| 4 | `content/spells.ts` | 6917 | Not yet read | `content` |
| 4 | `content/martial-features.ts` | 9971 | Not yet read | `content` (Feature implementations) |
| 4 | `content/fillers.ts` | 5949 | Read | `content` |
| 4 | `content/load-db.ts` | 9440 | Read | `adapter.out` seed catalog behind an `application` port |
| 5 | `scenario/maps.ts` | 1695 | Read | `scenario` |
| 5 | `scenario/library.ts` | 7008 | Read | `scenario` |
| 5 | `scenario/party.ts` | 7044 | Read | `scenario` |
| 6 | `opt/stats.ts` | 2256 | Read | `opt` |
| 6 | `opt/genome.ts` | 10192 | Read | `opt` |
| 6 | `opt/catalog.ts` | 10752 | Read | `opt` |
| 6 | `opt/evaluate.ts` | 6427 | Read | `opt` |
| 6 | `opt/party-evaluate.ts` | 7384 | Read | `opt` |
| 6 | `opt/nsga2.ts` | 7718 | Read | `opt` |
| 6 | `opt/roles.ts` | 2238 | Read | `opt` |
| 6 | `opt/reports.ts` | 7093 | Read | `opt` |
| 6 | `opt/campaign.ts` | 4917 | Read | `opt` |
| 6 | `opt/anchor.ts` | 2505 | Read | `opt` (see note) |
| 6 | `opt/ga.ts` | 3601 | Not yet read | `opt` (last; superseded by NSGA-II) |
| 7 | `cli/config.ts` | 7110 | Read | `adapter.in.web` DTOs + validation |
| 7 | `cli/engine.ts` | 8530 | Read | `application` use cases (`CliEngine` becomes the port) |
| 7 | `cli/report-io.ts` | 1878 | Not yet read | `application` `ReportStore` port |
| - | `cli/prompt*.ts`, `cli/flows.ts`, `cli/main.ts` | ~24k | not ported | dropped |

Notes:

- `opt/anchor.ts` (reference-anchor normalization) is not used by `reports.ts`, which min-max normalizes
  across the population ("a stand-in" per its header). Port it with `opt` but wire only what upstream
  wires.

## Tests to migrate (35 spec files upstream)

| Area | Specs | Port approach |
| --- | --- | --- |
| `rng`, `dice`, `grid`, `core` | 4 | deterministic assertions; statistical ones (dice means) use fixed seeds |
| `combat` | `actor`, `attack`, `aura`, `buff`, `conditions`, `control`, `damage`, `encounter`, `legendary`, `metamagic`, `spell` | direct ports; closed-form probability checks (`chanceAttackHits`, `chanceSaveSucceeds`, ...) are exact |
| `content` | `caster`, `character`, `martial-features`, `monster`, `srd-monsters`, `walking-skeleton` | `srd-monsters` must reproduce 341 compiled, 321 with a usable attack |
| `scenario`, `ai` | `library`, `party`, `policy` | direct |
| `opt` | `campaign`, `ga`, `genome`, `nsga2`, `party-evaluate`, `reports`, `roles`, `stats` | `nsga2` sort/crowding tests are pure and exact |
| `cli` | `config`, `flows`, `prompt` (37 tests) | `config` becomes DTO/validator tests; `flows` and `prompt` are not ported |

## Statistical conformance harness (replaces exact golden fixtures)

Because parity is statistical, a vendored Node script runs fixed scenarios in the TypeScript sim at high
N and writes win rates, damage means and confidence intervals to JSON committed under
`src/test/resources/reference/`. Java tests run the same scenarios with a **pinned seed** (so they are
deterministic, not flaky) and assert agreement within the combined confidence intervals. Pin and verify
each seed once when the test is written; a failure then means the port diverged, not bad luck.

Coverage candidates: the five L3 solo scenarios for a fixed set of genomes; a party scenario per level;
the walking-skeleton Champion vs three goblins; adventuring-day win rate for one nova and one sustained
build; and the NSGA-II L3 martial result (a Greatsword Barbarian should lead, ~95% win rate vs goblins).

## Hazards found while reading

These are details a straight translation would get wrong.

1. **Shared mutable `Feature` instances.** `feature.ts` says a Feature "belongs to one combatant and may
   hold per-turn state", but `opt/catalog.ts` builds `DarkOnesBlessingFeature` and `WildShapeFeature` once
   per catalog and `buildFromGenome` passes the same instances to every hero. That is harmless when
   evaluation is sequential and the features are stateless or reset in `onTurnStart`, but it is a data
   race under parallel evaluation. **Resolved in Phase 3**: `CombatantSpec` carries `FeatureFactory`s and each
   `Combatant` builds its own instances, so sharing is impossible by construction (`FeatureFactory.shared` exists
   for provably stateless features). Still to do when `martial-features.ts` is ported: read which features carry
   state and make their factories create new instances.
2. **Shared `Grid` and `MonsterTemplate`.** (`Grid` resolved in Phase 2: immutable once built.) Scenarios share one `Grid` across runs and `Grid.setTerrain`
   mutates. Terrain is read-only during fights, so make `Grid` immutable after construction in Java.
   Templates and `Spell` definitions (which hold function-valued scaling fields) must be immutable.
3. **Seeding by label.** Streams are addressed by labels built from combatant ids, weapon and spell names,
   for example `${self.id}:${profile.name}:${target.id}`. The Java port must keep label construction
   dependent on ids (not on draw order) or common random numbers are lost. Within a label, draws are
   sequential. `Encounter.concSeq` makes concentration-save labels unique per save; note
   `rollBuffSaveBonus(target, 'conc:' + this.concSeq)` reads the counter after it was incremented, a quirk
   with no statistical effect.
4. **Per-run state.** A `Random` holds a cache of stream generators and must be created per run, never a
   singleton. A run is `Encounter` + fresh `Combatant`s; nothing but read-only catalog data crosses runs.
5. **Ordering assumptions.** Initiative ties break by Dex modifier, then party before enemy, then id.
   `Combatant` keeps `Map`/`Set`/array state whose iteration order matters (`timed`, `buffs`, resource
   pools). Use `LinkedHashMap`/`LinkedHashSet` or lists to keep insertion order.
6. **Event log as the metrics source.** Evaluators compute damage, healing, control and buff assists by
   scanning `CombatEvent[]`. In Java use an event-sink port with a collecting sink (encounter endpoint,
   tests) and a counting/no-op sink (GA hot path) so evaluation does not allocate a log per fight.
7. **Round caps differ.** `Encounter.run` defaults to 100 rounds; all evaluators pass 50.
8. **Win definitions differ.** Solo: `winner === 'party' && hero.isConscious`; party: `winner === 'party'`.
   Efficiency counts a loss as the round cap (the fix that stopped NSGA-II rewarding fast deaths).
9. **Quirks to carry over deliberately**: monsters have `level 1` (proficiency +2, saves overridden);
   `melee_or_ranged` monster attacks are modeled as melee; Multiattack is approximated as N-1 extra swings
   of the strongest attack; opportunity attacks compare start-vs-end reach only; area damage is rolled
   once and shared.
10. **JSON numerics.** `Infinity` appears in crowding distance only inside NSGA-II and never in the report;
    keep it out of the serialized form (Jackson would reject it).
11. **Catalog scope.** The catalog is per level (3/5/11/17) and holds eight weapons and four armors plus
    fixed caster packages. Build all four eagerly at startup into immutable objects.

## Spec versus code (from `status.md`, relevant to the contract)

- The genome is single-class, standard-array, with fixed caster spell/gear packages (the spec describes
  multiclass, point-buy and spell genes). The API's `Genome` is exactly the implemented one.
- `optimize` is solo only; party-context optimization is a documented gap, hence `422 unsupported-context`.
- `effect-format.md` describes a declarative effect format; the implemented model is hand-written
  `Feature` hooks plus declarative `Spell`/`SpellKind` data. Port the implemented model.
