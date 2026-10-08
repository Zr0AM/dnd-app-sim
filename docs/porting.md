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
| 5 | `claude/phase-4-encounter` | `Feature`, `Encounter` loop, `CombatEvent`, tactical AI |
| 6 | `claude/phase-5-content` | seed catalog, content compilers, fillers |
| 7 | `claude/phase-6-scenarios-eval` | scenarios, party harness, evaluators, statistical conformance tests |
| 8 | `claude/phase-7-optimizer` | NSGA-II, reports, roles, campaign |
| 9 | `claude/phase-8-rest` | controllers, jobs, report stores (filesystem, D1) |
| 10 | `claude/phase-9-hardening` | limits, auth/rate limiting, profiling |

## Reference values from the TypeScript sim

`tools/reference/gen-rng.mts` runs the original `rng.ts` and `dice.ts` (Node 22 with
`--experimental-strip-types`, no install needed) and writes `src/test/resources/reference/rng.json`.
Regenerate after changing the baseline SHA:

```bash
DND_APP_DIR=/path/to/dnd-app node --experimental-strip-types tools/reference/gen-rng.mts
```

The RNG (xmur3 + mulberry32) is ported bit-exact, so `ReferenceParityTest` and the dice parity test compare
exactly. The rest of the engine is held to statistical equivalence (see the engine inventory).

## Port progress

| Area | Status |
| --- | --- |
| `rng`, `dice`, `core`, `grid` | done (Phase 2) |
| `combat`: `Combatant`, attack/damage/conditions/spell types, `Feature` interface | done (Phase 3) |
| `combat`: `Encounter` loop, `CombatEvent`, casting and weapon resolution | next |
| `ai`, `content`, `scenario`, `opt`, REST | not started |

Phase 3 tests that live elsewhere upstream and move with later ports: the "casting in the engine" block of
`spell.spec.ts` (needs `Encounter` and the spell catalog), and `aura`, `buff`, `control`, `legendary`,
`metamagic`, `encounter` specs.
