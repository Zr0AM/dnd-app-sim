# dnd-app-sim

Spring Boot port of the `sim/` build-optimization simulator from `Zr0AM/dnd-app`.
See [docs/porting.md](docs/porting.md) for decisions, the baseline SHA and the port map.

```bash
./gradlew build    # compile + test + coverage report
./gradlew bootRun  # http://localhost:8080/actuator/health
```

Requires JDK 21.

## Local development

```bash
./gradlew bootRun --args='--spring.profiles.active=local'
```

The `local` profile (`src/main/resources/application-local.yaml`) stores reports on disk under `out/reports`
and needs no Cloudflare credentials. To try the D1 adapter, set `CF_ACCOUNT_ID`, `CF_D1_DATABASE_ID` and
`CF_API_TOKEN` and enable it as described in that file; use a scratch D1 database, not production.
