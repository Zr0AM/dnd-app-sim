# dnd-app-sim

Spring Boot port of the `sim/` build-optimization simulator from `Zr0AM/dnd-app`.
See [docs/porting.md](docs/porting.md) for decisions, the baseline SHA and the port map.

```bash
./gradlew build                                              # compile + test + coverage report
SIM_API_KEYS=$(openssl rand -hex 24) ./gradlew bootRun       # http://localhost:8080/actuator/health
```

The service is closed by default: it needs at least one API key in `SIM_API_KEYS` (see
[docs/api/README.md](docs/api/README.md#security)) and refuses to start without one. The `local` profile below turns
authentication off for development.

Requires JDK 21.

## Modules

| Module | Holds | Depends on |
| --- | --- | --- |
| `sim-domain` | the engine, content compilers and optimizer (`domain.*`); plain Java | nothing |
| `sim-application` | use cases and ports (`application.*`) | `sim-domain`, SLF4J |
| `sim-content` | the SQLite seed catalog (`adapter.out.content`) and the seed SQL | `sim-domain` |
| `sim-json` | Jackson mapping for domain and application types (`adapter.json`) | `sim-application`, Jackson |
| `sim-reports` | filesystem and Cloudflare D1 report stores (`adapter.out.report`) | `sim-application`, Jackson |
| `sim-service` | the Spring Boot app: REST, security, configuration | all of the above |

Gradle stops a module from compiling against one it does not declare, and `ArchitectureTest` (in `sim-service`) checks
the package rules inside each module. Shared settings live in `buildSrc`, versions in `gradle/libs.versions.toml`.
Library modules publish test fixtures for one another (`TestJson`, `TestReports`, `TestMapper`). `./gradlew build`
also writes a coverage report across every module to `sim-service/build/reports/jacoco/testCodeCoverageReport`.

## Local development

```bash
./gradlew bootRun --args='--spring.profiles.active=local'
```

The `local` profile (`sim-service/src/main/resources/application-local.yaml`) stores reports on disk under `out/reports`
and needs no Cloudflare credentials. To try the D1 adapter, set `CF_ACCOUNT_ID`, `CF_D1_DATABASE_ID` and
`CF_API_TOKEN` and enable it as described in that file; use a scratch D1 database, not production.

## Seed data

The service reads the SRD reference data (monsters, classes, equipment, spell slots) from SQL bundled in
`sim-content/src/main/resources/db`, copied from `Zr0AM/dnd-app` by `scripts/sync-seeds.sh` (which records the upstream commit and
checksums in `db/SOURCE`). It is loaded into an in-memory SQLite database at startup (about half a second), read once
into immutable catalogs, and then closed. See [NOTICE](NOTICE) for the SRD attribution.

```bash
DND_APP_DIR=/path/to/dnd-app scripts/sync-seeds.sh
```

## Deployment (Cloudflare Containers)

The service runs as a Cloudflare Container behind the Worker in `worker/` (scaffolded from
`cloudflare/templates/containers-template`). The repository-root `Dockerfile` builds the `sim-service` boot jar and runs
it on a JRE 21 with the `prod` profile (`application-prod.yaml`): reports in D1, API keys required, rate limiting on, and
an executor sized to the instance's vCPUs. The Worker proxies `/api/v1/*` and `/actuator/health` to one named container
instance (jobs and rate-limit buckets are in memory) and returns `404` for everything else. Instance size, the idle
timeout before the container sleeps and the readiness wait are constants in `worker/src/index.ts`; Durable
Object-managed containers do not take them from `wrangler.jsonc`. Keep `INSTANCE` and `sim.executor.threads` in step.

`.github/workflows/deploy.yml` runs `./gradlew check` and the Worker tests on pull requests. On pushes to `main` it finds
the `dnd-app-sim` D1 database (creating it on the first deploy), runs `wrangler deploy` with the Worker secrets
`CF_ACCOUNT_ID`, `CF_D1_DATABASE_ID`, `CF_API_TOKEN` and `SIM_API_KEYS`, then runs `worker/scripts/smoke-test.sh` against
the deployed hostname (health, a keyed encounter, and an optimize report saved to D1 and read back). It needs these
repository secrets:

- `CLOUDFLARE_API_TOKEN`: Workers Scripts, Containers, Durable Objects and D1 Edit. The container reuses it for D1.
- `CLOUDFLARE_ACCOUNT_ID`
- `SIM_API_KEYS`: comma-separated API keys clients send as `X-API-Key` or `Authorization: Bearer`.

The `sim_report` table is created on first use. To deploy by hand from `worker/` instead:

```bash
npm ci
npx wrangler d1 create dnd-app-sim                       # prints the database ID
npx wrangler secret put CF_ACCOUNT_ID
npx wrangler secret put CF_D1_DATABASE_ID                # the ID from d1 create
npx wrangler secret put CF_API_TOKEN                     # a token with D1 Edit on the account
npx wrangler secret put SIM_API_KEYS                     # comma-separated keys
npx wrangler deploy                                      # builds and pushes the image, deploys the Worker
SIM_API_KEY=<key> scripts/smoke-test.sh https://dnd-app-sim.<subdomain>.workers.dev
```
