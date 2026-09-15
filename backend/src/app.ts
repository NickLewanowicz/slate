import { Elysia } from "elysia";
import type { Database } from "bun:sqlite";
import { checkAuth } from "./auth";
import { createLogger, type AppConfig } from "./config";
import { badRequest, internalError, notFound } from "./errors";
import { createSweeper, type Sweeper } from "./retention";
import { createWebhookDelivery, type WebhookDelivery } from "./webhook";
import { SlateValidator } from "./validation";
import { createHealthRoutes } from "./routes/health";
import { createSlateRoutes } from "./routes/slates";
import { createResponseRoutes } from "./routes/responses";
import { createDeviceRoutes } from "./routes/device";

export interface BuildAppOptions {
  db: Database;
  config: AppConfig;
}

export type SlateApp = Elysia & {
  webhook: WebhookDelivery;
  sweeper: Sweeper;
};

const ROUTE_HINT =
  "Available routes: GET /health, GET /, GET /api/ping, PUT /api/slates/{slateId}, GET /api/slates, GET /api/slates/{slateId}, DELETE /api/slates/{slateId}, GET /api/slates/{slateId}/responses, DELETE /api/slates/{slateId}/responses, GET /api/device/slates, GET /api/device/slates/{slateId}, POST /api/device/interactions. Contract: schema/api.md.";

const INTERNAL_HINT =
  "This is a server-side bug, not a problem with your request. Retry once; if it persists, check the server logs and include this message. Polling GET /api/slates/{id}/responses is unaffected.";

/**
 * Builds the Slate v2 app. Never listens — call `app.listen(port)` (or use
 * `app.handle(new Request(...))` in tests) separately. Accepts an injected
 * bun:sqlite Database so tests run against :memory:.
 */
export function buildApp(options: BuildAppOptions): SlateApp {
  const { db, config } = options;
  const log = createLogger(config);
  const validator = new SlateValidator(); // Ajv compile at boot — bad schemas fail fast
  const webhook = createWebhookDelivery(config, log);

  const app = new Elysia({ name: "slate-backend" })
    .onRequest(({ request }) => {
      const path = requestPath(request);
      return checkAuth(request, path, config.apiKey);
    })
    .onError(({ code, error, request }) => {
      let pathname = "/unknown";
      try {
        pathname = new URL(request.url).pathname;
      } catch {
        // keep fallback
      }
      const method = request.method;
      if (code === "NOT_FOUND") {
        return notFound(`No route for ${method} ${pathname}.`, ROUTE_HINT);
      }
      if (code === "PARSE" || code === "VALIDATION") {
        return badRequest(
          "invalid_json",
          "Request body could not be parsed.",
          `Send a valid JSON body with Content-Type: application/json. ${ROUTE_HINT}`,
        );
      }
      const message = error instanceof Error ? error.message : String(error);
      log("error", `unhandled error on ${method} ${pathname}: ${message}`);
      return internalError(`Unexpected server error: ${message}.`, INTERNAL_HINT);
    })
    .use(createHealthRoutes(config))
    .use(createSlateRoutes({ db, config, validator }))
    .use(createResponseRoutes({ db, config }))
    .use(createDeviceRoutes({ db, config, validator, webhook }));

  const sweeper = createSweeper(db, config, log);

  const withExtras = app as unknown as SlateApp;
  withExtras.webhook = webhook;
  withExtras.sweeper = sweeper;
  return withExtras;
}

function requestPath(request: Request): string {
  try {
    return new URL(request.url).pathname;
  } catch {
    return request.url;
  }
}
