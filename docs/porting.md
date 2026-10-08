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
| 7 | `claude/phase-6-content` | seed catalog, content compilers, fillers, spell catalog, class features |
| 8 | `claude/phase-7-scenarios-eval` | scenarios, party harness, evaluators, statistical conformance tests |
| 9 | `claude/phase-8-optimizer` | NSGA-II, reports, roles, campaign |
| 10 | `claude/phase-9-rest` | controllers, jobs, report stores (filesystem, D1) |
| 11 | `claude/phase-10-hardening` | limits, auth/rate limiting, profiling |

## Reference values from the TypeScript sim

Scripts under `tools/reference/` run the original TypeScript (Node 22 with `--experimental-strip-types`, no
install needed) and write expected output into `src/test/resources/reference/`. Regenerate after changing the
baseline SHA:

```bash
export DND_APP_DIR=/path/to/dnd-app
RUN="node --experimental-strip-types --import ./tools/reference/register-ts.mjs"
$RUN tools/reference/gen-rng.mts          # rng.json: seeds, streams, d20 and dice rolls
$RUN tools/reference/gen-encounters.mts   # encounters.json: full event logs and final states
```

`register-ts.mjs` is a tiny resolve hook: the upstream sources use extensionless imports (bundler-style), which
Node's ESM loader cannot follow on its own.

- **RNG and dice**: ported bit-exact, so `ReferenceParityTest` and the dice parity test compare exactly.
- **Encounters**: `scenarios.json` defines spells, four stateful test features and five fights (melee with
  resistances and crits; casters with control, buffs, healing and aura; a legendary boss with death saves and Lay
  on Hands; terrain with opportunity attacks, Hunter's Mark and Quickened Spell; concentration under heavy hits).
  Both engines run them with the same small "plan" interpreter (grammar in `gen-encounters.mts`, mirrored by
  `ScenarioRunner`). `EncounterParityTest` requires the Java log to match the TypeScript log **event for event**.
  All 18 event kinds and 557 events are covered. Because RNG streams are addressed by combatant/spell/target
  labels, any change to a rule or a label shows up as a divergence, and the test prints the first differing event.
- Beyond the encounter loop, the rest of the engine is held to statistical equivalence (see the engine inventory).
  When a scenario or plan change is needed, edit `scenarios.json` and the interpreter in both languages, regenerate,
  and re-run mutation checks (see the PR descriptions for the approach).

## Port progress

| Area | Status |
| --- | --- |
| `rng`, `dice`, `core`, `grid` | done (Phase 2) |
| `combat`: `Combatant`, attack/damage/conditions/spell types, `Feature` interface | done (Phase 3) |
| `combat`: `Encounter` loop, `CombatEvent`, casting and weapon resolution | done (Phase 4) |
| `ai` (tactical policy) | next |
| `content`, `scenario`, `opt`, REST | not started |

Tests still waiting on later ports: the "casting in the engine" block of `spell.spec.ts` and the `buff`, `control`
and `metamagic` specs use the spell catalog and/or the tactical AI. The data-driven parity scenarios already cover
the same mechanics (control, buffs, Quickened Spell) against the TypeScript engine.
