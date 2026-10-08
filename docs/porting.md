# Porting notes: dnd-app `sim/` -> dnd-app-sim

Source of truth: `Zr0AM/dnd-app`, directory `sim/`, pinned at commit
`8db9df32179604057009203c9effa5bc91c9dd6f` (merge of PR #73). Upstream changes after this SHA are not
tracked automatically; diff `sim/` and `docs/sim/` against it before each phase.

## Decisions

- REST replaces the CLI. `prompt*.ts`, `flows.ts`, `main.ts` are dropped; `config.ts` becomes request DTOs
  with Bean Validation; `engine.ts` becomes an application-layer port; `report-io.ts` becomes a `ReportStore`
  port (filesystem first, D1 later).
- `POST /api/v1/simulate/encounter` is new (not in the TypeScript sim).
- Parity target is statistical equivalence, not bit-exact. Determinism within Java (same seed, same output)
  and label-addressed RNG streams (common random numbers) are still required.
- Hexagonal layout: `domain` (pure Java) / `application` (use cases, ports) / `adapter.in.web` / `adapter.out`.
- Simulation runs on a bounded, core-sized executor, not virtual threads (CPU-bound).
- Reference data is loaded once at startup into an immutable catalog; no JPA, no `@Cacheable`.
- Reports persist to Cloudflare D1 via its HTTP query API (no JDBC driver exists). `sim.d1.*` holds the
  settings; credentials come from `CF_ACCOUNT_ID`, `CF_D1_DATABASE_ID`, `CF_API_TOKEN` and are never committed.
  The service itself runs on a JVM host, not on D1/Workers. `application-local.yaml` (profile `local`) uses the
  filesystem report store and needs no credentials.
- Seed SQL is copied from `dnd-app/docs/db` by a sync script (Phase 3), not a submodule.

## Port map

| TypeScript (`sim/src/`) | Java (`org.omnomnom.dnd.sim`) |
| --- | --- |
| `rng`, `dice`, `core`, `grid` | `domain.rng`, `domain.dice`, `domain.core`, `domain.grid` |
| `combat` (`actor`=`Combatant`, attack, damage, spell, conditions, feature, encounter) | `domain.combat` |
| `ai/policy` | `domain.ai` |
| `content` compilers (character, caster, monster, spells, multiattack, fillers, martial-features, ids) | `domain.content` |
| `content/load-db` | `adapter.out` seed catalog behind an `application` port |
| `scenario` | `domain.scenario` |
| `opt` (genome, catalog, evaluate, party-evaluate, nsga2, anchor, stats, roles, reports, campaign; `ga` last) | `domain.opt` |
| `cli/config`, `cli/engine`, `cli/report-io` | `adapter.in.web` DTOs, `application` ports |
| `cli/prompt*`, `cli/flows`, `cli/main` | dropped |

## Stacked PR plan

Each PR targets the previous PR's branch; merge bottom-up.

| PR | Branch | Content |
| --- | --- | --- |
| 1 | `claude/stoic-turing-gwooy9` | Phase 0: scaffold, hexagonal layout, D1 and local config |
| 2 | `claude/phase-1-contracts` | Phase 1: [REST contract](api/openapi.yaml), [API conventions](api/README.md), [engine inventory](discovery/engine-inventory.md) |
| 3 | `claude/phase-2-foundations` | `rng`, `dice`, `core`, `grid` |
| 4 | `claude/phase-3-combat` | `Combatant`, attack/damage/conditions/spell resolvers |
| 5 | `claude/phase-4-encounter` | `Encounter` loop, `CombatEvent`, event sinks, cross-language scenario parity |
| 6 | `claude/phase-5-ai` | tactical AI (`policy.ts`) |
| 7 | `claude/phase-6-content` | seed data + sync script, `ContentSource` port, SQLite adapter, monster compiler, multiattack data |
| 8 | `claude/phase-7-builds` | character and caster compilers, spell catalog, class features, fillers |
| 9 | `claude/phase-8-scenarios-eval` | scenario library, maps, reference-party harness, `Stats`, solo and party evaluators (take a hero factory; the genome and catalog arrive with the optimizer) |
| 10 | `claude/phase-9-optimizer` | genome and catalog, NSGA-II (with progress and cancel hooks), reports, role presets, adventuring-day campaign, anchor |
| 11 | `claude/phase-10-rest` | controllers, jobs, report stores (filesystem, D1) |
| 12 | `claude/phase-11-hardening` | limits, auth/rate limiting, profiling |

## Reference values from the TypeScript sim

Scripts under `tools/reference/` run the original TypeScript (Node 22 with `--experimental-transform-types`, no
install needed) and write expected output into `src/test/resources/reference/`. Regenerate after changing the
baseline SHA or the scenarios:

```bash
export DND_APP_DIR=/path/to/dnd-app
RUN="node --experimental-transform-types --import ./tools/reference/register-ts.mjs"
python3 tools/reference/gen-scenarios.py   # scenarios.json + sweep.json.gz + build-sweep.json.gz (inputs shared by both engines)
$RUN tools/reference/gen-rng.mts           # rng.json: seeds, streams, d20 and dice rolls
$RUN tools/reference/gen-encounters.mts    # encounters.json + sweep-expected.json + build-sweep-expected.json (TypeScript's answers)
$RUN tools/reference/gen-eval.mts          # eval-expected.json: the TS solo and party evaluators over 24 caster heroes
$RUN tools/reference/gen-opt.mts           # opt-expected.json: genome operators, every class through the solo evaluator, NSGA-II runs, reports, campaign, anchor
$RUN tools/reference/gen-content.mts       # content.json: all 341 monster templates (hashed), equipment, classes, slots
DUMP=sweep-042 $RUN tools/reference/gen-encounters.mts   # print one fight's events, to debug a mismatch
```

`--experimental-transform-types` (not just strip) is needed because the class-feature sources use TypeScript
parameter properties. `register-ts.mjs` is a tiny resolve hook: the upstream sources use extensionless imports (bundler-style), which
Node's ESM loader cannot follow on its own. `gen-scenarios.py` is deterministic (fixed seed, timestamp-free gzip);
a different Python minor version may draw different `random` sequences, in which case regenerate all of the above
together.

- **RNG and dice**: ported bit-exact, so `ReferenceParityTest` and the dice parity test compare exactly.
- **Hand-written fights** (`scenarios.json`, `EncounterParityTest`): spells, stateful test features and 9
  scenarios. Five exercise the engine through a small scripted "plan" interpreter (melee with resistances and crits;
  casters with control, buffs, healing and aura; a legendary boss with death saves and Lay on Hands; terrain with
  opportunity attacks, Hunter's Mark and Quickened Spell; concentration under heavy hits). Four put the **real
  tactical AI** in charge of every combatant on both sides. The Java log must match the TypeScript log **event for
  event** (895 events, all 18 kinds); a mismatch prints the first differing event.
- **Generated sweep** (`sweep.json.gz`, `SweepParityTest`): 360 small seeded fights with varied levels, spell
  loadouts, slot counts, wounds, positions, grid sizes and starting conditions, all AI-driven. The AI is a pure
  function of state, so hundreds of varied states probe its decision boundaries far better than a few set pieces.
  Compared by SHA-256 of each fight's canonical JSON (14325 events); on a mismatch the Java
  events are written to `build/parity/<name>.json`.
- **Build sweep** (`build-sweep.json.gz`, `BuildSweepParityTest`): 220 seeded fights whose combatants are *recipes*
  (class, subclass, level, abilities, gear, spells) built through the content layer on both sides: the seed data, the
  character and caster compilers, every class feature (rage, reckless attack, sneak attack, divine smite, martial
  arts, stunning strike, Wild Shape, Dark One's Blessing, Hunter's Mark, aura, Colossus Slayer), the spell catalog,
  the six reference fillers and compiled SRD monsters, driven by the tactical AI. Same SHA-256 comparison; a wrong AC,
  hit-point total, slot count or feature rider anywhere diverges the fight.
- **Evaluators** (`eval-input.json`, `EvaluatorParityTest`): the real TypeScript `evaluate` and `evaluatePartyBuild`
  run 24 caster heroes (6 classes at levels 3, 5, 11 and 17) through the real catalog, scenario library and reference
  parties; Java builds the same heroes from recipes and every metric, confidence interval and run count must match
  to 1e-9. Because upstream builds its heroes from a genome and its catalog while Java uses recipes, a match also shows
  the two hero definitions agree. Martial heroes need the genome and catalog, so they are covered once those land
  (the optimizer phase); their combat is already covered by the build sweep.
- **Optimizer** (`opt-expected.json`, `OptimizerParityTest`): 90 chained random, mutate, mutate and crossover
  steps compared genome for genome (same labeled streams, same draw order); all 12 classes x 4 checkpoint levels (192
  genomes) through `buildFromGenome` and the solo evaluator, which also gives the martial evaluator parity deferred
  from Phase 8; the NSGA-II sort and crowding cores on tied synthetic points; three full NSGA-II runs (two at level 3,
  one at 11, one with a class restriction) compared for every individual's genome, objectives, rank and crowding, then
  their reports, role rescoring and campaign annotation; the adventuring day for 16 builds; and the anchor.
- Because RNG streams are addressed by combatant/spell/target labels, any change to a rule, a label or an AI
  decision shows up as a divergence. The rest of the engine (content, optimizer) is held to statistical equivalence.

### Content parity

`gen-content.mts` runs the TypeScript loaders and compilers over the same seed database and
`SqliteContentSourceTest` compares: a SHA-256 of **every one of the 341 compiled monster templates**, three readable
templates, every weapon (38) and armor (13), the 12 classes, and class progression and spell slots for every class at
levels 1-19. The seeds are copied, not rebuilt: `scripts/sync-seeds.sh` pins the upstream commit in
`src/main/resources/db/SOURCE`.

Known upstream quirk carried over: flat-damage weapons (the Blowgun) have no dice in the seeds and upstream ignores
their `damageFlat`, so they deal only the ability modifier; the Java port behaves the same (0 dice).

### Mutation checking the harness

The harness is only as good as the changes it can detect, so each engine/AI port was mutation-tested: introduce a
plausible bug (flip a comparison, change a constant, drop a branch), confirm a parity test fails, restore. For the AI,
33 of 37 mutants are caught. The 4 survivors are *equivalent mutants*, changes that cannot alter behavior with the
real catalog: the `max(1, rangeFt/5)` clamp in `approach` (the shortest upstream spell range is 5 ft), the
`isConscious` filter in heal-target selection (dying allies are returned earlier, so it is redundant), the
buff-cast result check (the cast cannot fail once slot, range and action are validated), and the quickened-cantrip
range pre-check (the engine's `castSpell` re-validates range with no side effects).

Phase 7 (content builds) was mutation-checked by a **sample** only: a seeded random subset of mutants of
`CharacterCompiler` (about 20 of 75 tried before the run was cut short; the full run at about 20 s per mutant was too
slow). It exposed real unit-test gaps (plain unarmored AC, the hit-point floor, class gating of Sneak Attack, Divine
Smite and Aura of Protection, the level boundaries for Aura, Focus Points, Stunning Strike and Reckless Attack, Rage's
short-rest recharge), now covered in `CharacterCompilerTest`. The tie in `weaponAbility` (STR and DEX modifiers equal)
is an equivalent mutant. `CasterCompiler`, the feature classes and `SpellCatalog` were **not** mutation-sampled; they
are covered by the build sweep and unit tests only. Finishing that is tracked for the hardening phase.

Phase 8 (scenarios, party harness, evaluators) was mutation-sampled on all nine files (about 10 mutants each): 21
survivors exposed unpinned deployment cells and map sizes, the default run counts, the empty-result and one-run edge
cases, the allies-alive fraction and the objective signs, all now covered by `MapsTest`, `EvaluatorTest` and
`ScenarioTest`. Two survivors are equivalent mutants: the sample-standard-deviation form `(v-m)*(v+m)` sums to the same
value as `(v-m)^2` whenever `m` is the mean, and the Wilson upper clamp at `p = 1` differs only by rounding.

Deliberate differences in the optimizer port:

- `opt/ga.ts` (the scalar GA) is **not ported**: NSGA-II supersedes it and the API exposes no GA.
- `describeGenome` prints the level (`L3 fighter ...`) where upstream prints a literal `L?` placeholder.
- Reports are plain records; the run key hashes a canonical JSON string the caller supplies, so a key matches upstream
  only when the config text is identical (the parity test uses identical text). The API contract does not promise
  equal keys across implementations.
- NSGA-II gains a progress listener and a cancellation check at generation boundaries, which the job service uses.
- Evaluation inside a run is single-threaded and cached by genome key; parallelism belongs to the job layer (one run per
  executor thread), because results must not depend on thread timing.

Phase 9 (optimizer) was mutation-sampled on seven files (14 mutants each): 28 survivors. The real gaps (default
options and campaign constants, the default leaderboard size, run-key serialization of lists, nested maps and strings,
the catalog's subclass and caster-package data, the pinned role weights, the zero-day and one-day campaign edges, the
benchmark genome) are covered by `OptimizerDetailsTest`; run keys are pinned to values produced by the TypeScript
`runKey`. Equivalent mutants: `<` to `<=` on a continuous random draw in crossover, and the `i > 0` short rest before
the first fight (a fresh hero is already at full HP and resources). Not killable with real content: `||` for `&&` in
the day-clear test, because a solo party win with an unconscious hero cannot happen and the 50-round cap draw is
vanishingly rare.

## Port progress

| Area | Status |
| --- | --- |
| `rng`, `dice`, `core`, `grid` | done (Phase 2) |
| `combat`: `Combatant`, attack/damage/conditions/spell types, `Feature` interface | done (Phase 3) |
| `combat`: `Encounter` loop, `CombatEvent`, casting and weapon resolution | done (Phase 4) |
| `ai` (tactical policy) | done (Phase 5) |
| `content`: seed data, `ContentSource` + SQLite adapter, monster compiler | done (Phase 6) |
| `content`: character/caster compilers, spell catalog, class features, fillers | done (Phase 7) |
| `scenario` (maps, library, party harness), `opt/stats`, solo and party evaluators | done (Phase 8) |
| `opt`: genome, catalog, NSGA-II, reports, role presets, campaign, anchor | done (Phase 9) |
| `application` use cases, REST, jobs, report stores (filesystem, D1) | next |

Tests still waiting on later ports: the "casting in the engine" block of `spell.spec.ts` and the `buff`, `control`
and `metamagic` specs use the spell catalog and/or the tactical AI. The data-driven parity scenarios already cover
the same mechanics (control, buffs, Quickened Spell) against the TypeScript engine.
