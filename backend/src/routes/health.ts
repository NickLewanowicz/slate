import { Elysia } from "elysia";
import { jsonResponse } from "../errors";
import type { AppConfig } from "../config";

export function createHealthRoutes(config: AppConfig) {
  return new Elysia({ name: "slate-health" })
    .get("/health", () => jsonResponse({ status: "ok" }))
    .get(
      "/",
      () =>
        jsonResponse({
          service: "slate-backend",
          version: config.version,
          hint: "Home-screen surface where your agent asks and answers. Contract: schema/api.md. Health: GET /health; authed ping: GET /api/ping.",
        }),
    );
}
