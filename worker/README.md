# dnd-app-sim Worker

Cloudflare Worker that fronts the `sim-service` container, scaffolded from
[`cloudflare/templates/containers-template`](https://github.com/cloudflare/templates/tree/main/containers-template).
It proxies `/api/v1/*` and `/actuator/health` to a single Durable Object-managed container built from the repository-root
`Dockerfile`. See the [repository README](../README.md#deployment-cloudflare-containers) for setup and secrets.

```bash
npm ci
npm test              # unit tests; no Docker
npx tsc --noEmit
npx wrangler deploy   # needs Docker, builds and pushes the image
npm run cf-typegen    # after changing wrangler.jsonc
```
