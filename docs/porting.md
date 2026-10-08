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
