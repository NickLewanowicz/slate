import { Elysia } from "elysia";
import type { Database } from "bun:sqlite";
import type { AppConfig } from "../config";
import { badRequest, jsonResponse, notFound } from "../errors";
import { parseNonNegativeInt, parsePositiveInt } from "../http";
import { shapeInteractionRow, type InteractionDbRow } from "../store";
import { NOT_FOUND_SLATE_HINT } from "./slates";

const DEFAULT_LIMIT = 50;
const MAX_LIMIT = 200;

const LIMIT_HINT = `limit must be an integer between 1 and ${MAX_LIMIT} (default ${DEFAULT_LIMIT}). Example: GET /api/slates/home/responses?limit=100`;

export function createResponseRoutes(deps: { db: Database; config: AppConfig }) {
  const { db } = deps;
  return new Elysia({ name: "slate-responses" })
    .get("/api/slates/:slateId/responses", ({ params, query }) => {
      const slateId = params["slateId"];
      if (!slateExists(db, slateId)) {
        return notFound(`Slate "${slateId}" not found.`, NOT_FOUND_SLATE_HINT(slateId));
      }

      let since = 0;
      if (query["since"] !== undefined && query["since"] !== "") {
        const parsed = parseNonNegativeInt(query["since"]);
        if (!parsed.ok) {
          return badRequest(
            "invalid_query",
            `since must be a non-negative integer interaction id, got "${query["since"]}".`,
            "since is the id of the last interaction you already have (0 = from the start; otherwise the cursor returned by the previous page). Example: GET /api/slates/home/responses?since=42&limit=50",
          );
        }
        since = parsed.value;
      }

      let limit = DEFAULT_LIMIT;
      if (query["limit"] !== undefined && query["limit"] !== "") {
        const parsed = parsePositiveInt(query["limit"]);
        if (!parsed.ok || parsed.value > MAX_LIMIT) {
          return badRequest("invalid_query", `Invalid limit "${query["limit"]}".`, LIMIT_HINT);
        }
        limit = parsed.value;
      }

      // Fetch one extra row to compute hasMore without a second query.
      const rows = db
        .query<InteractionDbRow, [string, number, number]>(
          `SELECT id, slate_id, kind, element_id, question_id, option_id, option_label, item_id, value, client_at, stale, created_at
           FROM interactions WHERE slate_id = ? AND id > ? ORDER BY id ASC LIMIT ?`,
        )
        .all(slateId, since, limit + 1);

      const hasMore = rows.length > limit;
      const page = hasMore ? rows.slice(0, limit) : rows;
      const responses = page.map(shapeInteractionRow);
      const cursor = responses.length > 0 ? responses[responses.length - 1].id : since;
      return jsonResponse({ responses, cursor, hasMore });
    })
    .delete("/api/slates/:slateId/responses", ({ params, query }) => {
      const slateId = params["slateId"];
      if (!slateExists(db, slateId)) {
        return notFound(`Slate "${slateId}" not found.`, NOT_FOUND_SLATE_HINT(slateId));
      }
      let beforeId: number | null = null;
      if (query["beforeId"] !== undefined && query["beforeId"] !== "") {
        const parsed = parsePositiveInt(query["beforeId"]);
        if (!parsed.ok) {
          return badRequest(
            "invalid_query",
            `beforeId must be a positive integer interaction id, got "${query["beforeId"]}".`,
            "beforeId acknowledges (deletes) every interaction with id <= beforeId — pass the cursor (last id you processed) so nothing you saw is re-delivered. Omit the parameter to delete all responses for the slate. Example: DELETE /api/slates/home/responses?beforeId=42",
          );
        }
        beforeId = parsed.value;
      }
      const deleted =
        beforeId !== null
          ? db.run("DELETE FROM interactions WHERE slate_id = ? AND id <= ?", [slateId, beforeId]).changes
          : db.run("DELETE FROM interactions WHERE slate_id = ?", [slateId]).changes;
      return jsonResponse({ deleted });
    });
}

function slateExists(db: Database, slateId: string): boolean {
  return db.query<{ n: number }, [string]>("SELECT COUNT(*) AS n FROM slates WHERE slate_id = ?").get(slateId)!.n > 0;
}
