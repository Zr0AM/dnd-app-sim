import { DurableObject } from "cloudflare:workers";
import { Hono } from "hono";

const PORT = 8080;
// JVM cold starts are slow, so keep an idle container warm well past a typical polling gap.
const INACTIVITY_TIMEOUT_MS = 20 * 60 * 1000;
// Spring Boot plus the seed catalog takes seconds to start; allow up to about two minutes.
const READINESS_ATTEMPTS = 120;
const INSTANCE = "standard-4";
// Jobs and rate-limit buckets live in one JVM, so every request goes to the same named instance.
const INSTANCE_NAME = "sim";

export class SimContainer extends DurableObject<Env> {
	private starting: Promise<void> | undefined;
	private monitoring: Promise<void> | undefined;

	constructor(ctx: DurableObjectState, env: Env) {
		super(ctx, env);
		const container = ctx.container;
		if (container?.running) {
			// Restore the timeout and exit monitoring after a DO restart.
			void ctx.blockConcurrencyWhile(() =>
				container.setInactivityTimeout(INACTIVITY_TIMEOUT_MS),
			);
			this.observeExit();
		}
	}

	async fetch(request: Request): Promise<Response> {
		// Share startup across concurrent requests, including after a failed attempt.
		this.starting ??= this.startAndWaitForPort().finally(() => {
			this.starting = undefined;
		});
		await this.starting;

		const url = new URL(request.url);
		url.protocol = "http:";
		url.host = "container";
		const forwarded = new Request(url, request);
		forwarded.headers.delete("host");
		return this.ctx.container!.getTcpPort(PORT).fetch(forwarded);
	}

	private async startAndWaitForPort(): Promise<void> {
		const container = this.ctx.container!;
		if (!container.running) {
			container.start({
				image: container.images.base,
				instance: INSTANCE,
				// The D1 report store calls the Cloudflare API over the internet.
				enableInternet: true,
				env: {
					SERVER_PORT: String(PORT),
					CF_ACCOUNT_ID: this.env.CF_ACCOUNT_ID,
					CF_D1_DATABASE_ID: this.env.CF_D1_DATABASE_ID,
					CF_API_TOKEN: this.env.CF_API_TOKEN,
					SIM_API_KEYS: this.env.SIM_API_KEYS,
				},
			});
		}
		this.observeExit();
		await container.setInactivityTimeout(INACTIVITY_TIMEOUT_MS);

		// running means startup was requested, not that the HTTP server is ready.
		const port = container.getTcpPort(PORT);
		for (let attempt = 0; attempt < READINESS_ATTEMPTS; attempt++) {
			try {
				const response = await port.fetch("http://container/actuator/health", {
					signal: AbortSignal.timeout(1000),
				});
				await response.body?.cancel();
				if (response.ok) return;
			} catch {
				// The port may not be listening yet. Retry the readiness probe only.
			}
			await scheduler.wait(500);
		}
		throw new Error(`Container did not become ready on port ${PORT}`);
	}

	private observeExit(): void {
		if (this.monitoring) return;
		this.monitoring = this.ctx
			.container!.monitor()
			.then(() => console.log("Container exited successfully"))
			.catch((error: unknown) => console.error("Container failed:", error))
			.finally(() => {
				this.monitoring = undefined;
			});
		this.ctx.waitUntil(this.monitoring);
	}
}

const app = new Hono<{ Bindings: Env }>();

const toContainer = (env: Env, request: Request) =>
	env.SIM_CONTAINER.getByName(INSTANCE_NAME).fetch(request);

app.all("/api/v1/*", (c) => toContainer(c.env, c.req.raw));
app.get("/actuator/health", (c) => toContainer(c.env, c.req.raw));

app.onError((error, c) => {
	console.error("Container request failed:", error);
	return c.text(
		"Container request failed. Check the Worker logs and retry.",
		502,
	);
});

export default app;
