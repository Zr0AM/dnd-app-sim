# REST API design

Contract: [`openapi.yaml`](openapi.yaml) (OpenAPI 3.1, validated). This page holds the conventions the
schema cannot express and the reasoning behind them. Everything marked **proposed** is a default to
confirm, not a measured value.

## CLI to REST mapping

The REST service replaces the `sim/src/cli` entry point of `Zr0AM/dnd-app`. The menu, prompt and
argv code is dropped; the pure parts survive as request DTOs and application ports.

| CLI (`npm run sim`) | REST | Notes |
| --- | --- | --- |
| `optimize` / `run` | `POST /api/v1/simulate/optimize` (202 + job) | `RunConfig` minus `outDir` |
| `eval` | `POST /api/v1/simulate/eval` | explicit genome replaces the per-seed random genome plus gear overrides |
| `campaign` (one build) | `POST /api/v1/simulate/campaign` | |
| `campaign` (saved report) | `POST /api/v1/reports/{id}/campaign` (202 + job) | overwrites the report, as the CLI did |
| `rescore` | `POST /api/v1/reports/{id}/rescore` | pure; `save` opt-in |
| `browse` | `GET /api/v1/content/*` | classes, roles, scenarios, plus monsters, weapons, armors, maps, party templates |
| `--help`, `prompt.ts`, `flows.ts`, `main.ts` | dropped | OpenAPI and Swagger UI replace them |
| (none) | `POST /api/v1/simulate/encounter` | new capability |

Flag validation in `cli/config.ts` (`applyFlags`) becomes Bean Validation plus a small semantic
validator; the error messages there are the source for the `Problem.errors` text.

## Determinism, seeds and common random numbers

- A request with the same body (including `seed`) returns the same result. If `seed` is omitted the
  server draws one and echoes it, so any run can be reproduced.
- `seed` is an unsigned 32-bit integer (the TypeScript RNG is 32-bit). Values outside the range are
  rejected rather than silently wrapped.
- Per-run seeds derive from `(seed, scenarioKey, runIndex)`. `scenarioKey` identifies the enemies and
  map, never the party or genome. This preserves the TypeScript property that two builds in one
  scenario face identical enemy rolls (paired comparison, far fewer samples for a given confidence).
- Parity target with the TypeScript sim is **statistical equivalence**, not identical event logs, so
  the RNG algorithm need not match `mulberry32`/`xmur3`. Determinism within Java and label-addressed
  streams are required.
- `runKey` (and so `reportId`) is a stable hash of the config in the Java implementation. It is not
  equal to the TypeScript `runKey` for the same config.

## Jobs and progress

`optimize` and report campaign annotation are always jobs. States: `queued`, `running`, `succeeded`,
`failed`, `cancelled`. Progress for NSGA-II is `initial-population` (1 step), then `generation`
(`completed` of `generations`), then `campaign` when requested. The TypeScript `runNsga2` has no
progress callback (cli.md lists it as a later nicety); the Java domain adds a listener port.

Cancellation is cooperative at generation boundaries. Jobs are held in memory in v1 (a restart loses
queued and running jobs; finished work lives in the report store). Persisting jobs is out of scope
until the D1 report store lands.

## Reports

- `GET /reports/{id}` returns the TypeScript `RunReport` (version 1) field for field. Additions are
  optional and additive: per-entry `ci` (the evaluator already computes win-rate, damage and
  HP-retained intervals but the TypeScript report drops them; the D1 `SimResult` design wants them) and
  top-level `engine`. Readers must ignore unknown fields.
- `reportId` is the `runKey`. Re-running an identical config overwrites, as `sim/out/<runKey>.json`
  did. Whether to keep versions instead is an open question (below).
- `describeGenome` no longer emits the `L?` placeholder noted in status.md; descriptions include the
  level.
- Weighting stays post hoc. Raw objectives and per-objective bounds are stored so clients and the
  `rescore` endpoint reweight without re-simulating (the contract `ui-integration.md` relies on).

## Encounter endpoint

- The engine only needs a list of combatants, a grid and a round cap, so the request is a plain party
  and enemy list. Reference-party composition (R6/R4/R3 and role-slot substitution in
  `scenario/party.ts`) is data the client can read from `GET /content/party-templates` and compose;
  the encounter endpoint does not duplicate it.
- Party members are `build` (a genome) or `filler` (a frozen role build). Enemies are monster slugs
  with counts, or a library `scenarioId`, never both.
- Placement uses the map's deployment cells in order. `open-field` and `corridor-chokepoint` have one
  party start (`heroStart`) and six enemy cells; `party-field` (22x14) has six party and 30 enemy
  cells. Over-capacity is `422 over-capacity`. Single-hero requests default to `open-field`, larger
  parties to `party-field`.
- Monsters with no usable attack (about 20 of 341 SRD monsters: swarms, non-combatants, save-only
  attackers) are rejected with `422 monster-has-no-attacks` rather than
  producing a fight where they idle. **Proposed**; the alternative is to allow them.
- `includeLog` returns one run's `CombatEvent[]`, selected by `logRun`. The 18 event kinds mirror
  `sim/src/combat/encounter.ts`. In Java the events are domain records in a sealed interface; the
  `kind` discriminator is added by a Jackson mixin in the web adapter so `domain` stays free of
  Jackson (enforced by ArchUnit).
- Per-member `avgDamageDealt` counts `attack`, `opportunity`, `spell` and `legendary` events. The
  TypeScript hero counters omit `legendary`, which undercounts bosses.

## Genome input

`GenomeInput` requires only `classSlug`. The CLI filled the rest from a seeded random genome and then
called `repair`; the API keeps that behavior and echoes the effective `Genome` so callers see what ran.
Illegal combinations (a shield with a two-handed weapon, a barbarian in armor) are repaired, not
rejected, matching the optimizer, which relies on repair as its legality validator.

## Limits (proposed, `sim.limits.*`)

| Limit | Default | Why |
| --- | --- | --- |
| `encounter.runs` | 2000 | keeps the synchronous call to a few seconds |
| `eval.runsPerScenario` | 200 | |
| `optimize.populationSize` | 128 | `thorough` preset is 64 |
| `optimize.generations` | 100 | `thorough` preset is 24 |
| `optimize.evalRuns` | 64 | `thorough` preset is 20 |
| queued jobs | 8 | beyond this, `429` with `Retry-After` |

The executor is a bounded, core-sized pool separate from request threads (simulation is CPU-bound, so
virtual threads would not help). Both limits above and the pool size are configuration.

## Errors

RFC 9457 `application/problem+json`. Stable `code` values include `unknown-monster`,
`monster-has-no-attacks`, `over-capacity`, `unsupported-context` (party-context optimize), `unknown-role`,
`invalid-seed`. `errors[]` carries field-level validation detail. `400` is malformed input, `422`
well-formed but invalid, `409` cancelling a finished job, `429` queue full.

## Security

Not designed in Phase 1. The endpoints are CPU-expensive, so authentication or rate limiting is needed
before any public deployment; this is tracked for the hardening phase. The OpenAPI document declares no
security scheme yet.

## Implementation notes (Phase 10)

- **Status codes**: `400` covers anything the schema forbids (types, bounds, enums, `exactly one of enemies or scenarioId`,
  a build without a genome); `422` covers well-formed requests that cannot be honored. The 422 `code` values in use are
  `unsupported-level`, `unknown-monster`, `monster-has-no-attacks`, `over-capacity`, `unknown-scenario`, `unknown-map`,
  `map-mismatch`, `duplicate-id`, `invalid-log-run`, `unknown-weapon`, `unknown-armor`, `invalid-ability-assignment`
  and `unknown-role`. The 400 codes are `invalid-request` (with `errors[]`) and `malformed-json`.
- **`seed` on `eval` and `campaign`** only completes an under-specified genome (as the CLI's per-seed random genome did).
  The evaluators' own seeds depend on the scenario and run index alone (common random numbers), so the numbers for a
  fully specified genome do not depend on `seed`. The effective genome is echoed; sending it back reproduces the result.
- **Encounter seeding**: run `i` uses `(seed, map + enemy slugs, i)`, never the party, so two parties facing the same
  enemies under one seed get identical enemy rolls.
- **Null policy**: `Genome.armorName` is always present (null means unarmored); `fightingStyle`, `MemberStats.genome`,
  `EvalResponse.ci` and the optional scenario fields are omitted when unset.
- **Contract check**: `OpenApiConformanceTest` validates real responses (every event kind seen, all content lists,
  problem bodies) against `openapi.yaml`, so the document and the service cannot drift unnoticed.
- **Jobs** (`optimize`, `reports/{id}/campaign`) run on the bounded simulation executor; a full queue is `429` with
  `Retry-After`. Cancelling a queued job is immediate; a running job stops at the next generation boundary and reports
  `cancelled` once it has. Cancelling a finished job is `409 job-finished`. Jobs are in memory (a restart loses them);
  the 200 most recent finished jobs are remembered. An optimization with `campaign: true` annotates with 12 days.
- **Reports** are stored by run key. A report's `config` is the effective request (level, role, classes, resolved `ga`
  numbers, campaign, seed), so the same request yields the same key and overwrites. The key hashes the config's JSON, so
  it is stable but not equal to the TypeScript key.
- **Report stores**: `filesystem` (default; `<runKey>.json`, written atomically, in `sim.report-store.directory`) or `d1`
  (table `sim_report`, created on first use, over Cloudflare's HTTP query API with the token from `CF_API_TOKEN`; asking
  for `d1` without all of `CF_ACCOUNT_ID`, `CF_D1_DATABASE_ID`, `CF_API_TOKEN` fails at startup). The D1 table is this
  service's own; mapping reports into the app's planned `SimRun`/`SimResult` tables is a separate integration.
- **Rescore** takes exactly one of `role` and `weights` (`400` otherwise); unknown role or objective names are `422`.
- **Deferred to hardening**: configurable `sim.limits.*` (the schema maxima are enforced as 400s today), authentication
  and rate limiting.

## Open questions

1. **Report versioning**: overwrite by `runKey` (CLI behavior, current draft) or keep immutable versions
   so a campaign annotation does not replace the one-shot result? The D1 `SimRun` design suggests runs are
   immutable with an engine/content hash.
2. **Rejecting no-attack monsters** (above).
3. **Hosting and auth**, which also decide whether the D1 report store is the default in deployed
   environments.
4. **Client-side generation**: should the Angular app generate its client from `openapi.yaml`?
