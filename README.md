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

## Local development

```bash
./gradlew bootRun --args='--spring.profiles.active=local'
```

The `local` profile (`src/main/resources/application-local.yaml`) stores reports on disk under `out/reports`
and needs no Cloudflare credentials. To try the D1 adapter, set `CF_ACCOUNT_ID`, `CF_D1_DATABASE_ID` and
`CF_API_TOKEN` and enable it as described in that file; use a scratch D1 database, not production.

## Seed data

The service reads the SRD reference data (monsters, classes, equipment, spell slots) from SQL bundled in
`src/main/resources/db`, copied from `Zr0AM/dnd-app` by `scripts/sync-seeds.sh` (which records the upstream commit and
checksums in `db/SOURCE`). It is loaded into an in-memory SQLite database at startup (about half a second), read once
into immutable catalogs, and then closed. See [NOTICE](NOTICE) for the SRD attribution.

```bash
DND_APP_DIR=/path/to/dnd-app scripts/sync-seeds.sh
```
