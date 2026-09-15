import { Elysia } from "elysia";
import type { Database } from "bun:sqlite";
import type { AppConfig } from "../config";
import { badRequest, jsonResponse, notFound, payloadTooLarge } from "../errors";
import { contentLengthTooLarge, isPlainObject, nowIso, parseJsonBody } from "../http";
import { listSlates } from "../store";
import { MAX_BODY_BYTES } from "./slates";
import {
  applyTodoCheck,
  getSlate,
  insertInteraction,
  interactionsByIds,
  isExpired,
  storeMutatedSpec,
  type InteractionRecord,
} from "../store";
import { enforceRetentionForSlate } from "../retention";
import type { WebhookDelivery } from "../webhook";
import type { SlateValidator } from "../validation";
import { NOT_FOUND_SLATE_HINT } from "./slates";

export function createDeviceRoutes(deps: {
  db: Database;
  config: AppConfig;
  validator: SlateValidator;
  webhook: WebhookDelivery;
}) {
  const { db, config, validator, webhook } = deps;

  return new Elysia({ name: "slate-device" })
    .get("/api/ping", () => jsonResponse({ ok: true, serverTime: nowIso(), version: config.version }))
    .get("/api/device/slates", () => jsonResponse({ slates: listSlates(db) }))
    .get("/api/device/slates/:slateId", ({ params, request }) => {
      const slateId = params["slateId"];
      const row = getSlate(db, slateId);
      if (!row) return notFound(`Slate "${slateId}" not found.`, NOT_FOUND_SLATE_HINT(slateId));

      const etag = `"${row.content_hash}"`;
      const ifNoneMatch = request.headers.get("if-none-match");
      if (ifNoneMatch && etagMatches(ifNoneMatch, row.content_hash)) {
        return new Response(null, { status: 304, headers: { etag } });
      }
      return jsonResponse(JSON.parse(row.spec_json), 200, { etag });
    })
    .post("/api/device/interactions", async ({ request }) => {
      if (contentLengthTooLarge(request, MAX_BODY_BYTES)) {
        return payloadTooLarge(
          `Request body exceeds the ${MAX_BODY_BYTES / 1024} KB transport limit.`,
          `Interaction batches are tiny (max 50 interactions, value ≤1000 chars each). A body this large is always wrong — trim it before retrying.`,
        );
      }
      const parsed = await parseJsonBody(request);
      if (!parsed.ok) {
        return badRequest(
          "invalid_json",
          "Request body is not valid JSON.",
          'Send Content-Type: application/json with one batch, e.g. {"interactions":[{"seq":"<uuid>","slateId":"home","kind":"answer","elementId":"q1","questionId":"q1","optionId":"retry","optionLabel":"Retry"}]}. Contract: schema/interactions.schema.json',
        );
      }

      const result = validator.validateInteractionBatch(parsed.body);
      if (!result.ok) {
        return badRequest("validation_error", result.errors.message, result.errors.hint);
      }

      const batch = (parsed.body as { interactions: Record<string, unknown>[] }).interactions;

      // All-or-nothing: reject the whole batch if any interaction targets an unknown slate.
      const wanted = [...new Set(batch.map((i) => String(i["slateId"])))];
      const existing = wanted.filter((id) => getSlate(db, id) !== null);
      const missing = wanted.filter((id) => !existing.includes(id));
      if (missing.length > 0) {
        return badRequest(
          "unknown_slate",
          `Interaction references unknown slate "${missing[0]}".`,
          `No spec exists for ${missing.map((m) => `"${m}"`).join(", ")}. Push one first with PUT /api/slates/<slateId>.` +
            (existing.length > 0 ? ` Existing slates: ${existing.join(", ")}.` : " No slates exist yet.") +
            " Nothing from this batch was applied (all-or-nothing).",
        );
      }

      // Load the current stored spec per slate once (stale flag + todo mutations).
      const specs = new Map<string, Record<string, unknown>>();
      for (const id of existing) {
        const row = getSlate(db, id)!;
        specs.set(id, JSON.parse(row.spec_json));
      }

      const now = nowIso();
      const acceptedIds: number[] = [];
      const touchedSpecs = new Set<string>();
      let accepted = 0;
      let duplicate = 0;

      const applyBatch = db.transaction(() => {
        for (const raw of batch) {
          const interaction: InteractionRecord = {
            seq: String(raw["seq"]),
            kind: String(raw["kind"]),
            elementId: asOptionalString(raw["elementId"]),
            questionId: asOptionalString(raw["questionId"]),
            optionId: asOptionalString(raw["optionId"]),
            optionLabel: asOptionalString(raw["optionLabel"]),
            itemId: asOptionalString(raw["itemId"]),
            value: asOptionalString(raw["value"]),
            clientAt: asOptionalString(raw["clientAt"]),
          };
          const slateId = String(raw["slateId"]);
          const stale = isExpired(specs.get(slateId), new Date(now));
          const { inserted, rowId } = insertInteraction(db, slateId, interaction, stale, now);
          if (!inserted) {
            duplicate += 1;
            continue;
          }
          accepted += 1;
          acceptedIds.push(rowId);

          // Server owns the canonical checked state.
          if ((interaction.kind === "check" || interaction.kind === "uncheck") && interaction.elementId && interaction.itemId) {
            const spec = specs.get(slateId)!;
            const mutated = applyTodoCheck(spec, interaction.elementId, interaction.itemId, interaction.kind === "check");
            if (mutated) touchedSpecs.add(slateId);
          }
        }
        for (const slateId of touchedSpecs) {
          storeMutatedSpec(db, slateId, specs.get(slateId)!);
        }
        // Retention on insert (per affected slate).
        for (const slateId of new Set([...touchedSpecs, ...wanted])) {
          enforceRetentionForSlate(db, slateId, config);
        }
      });
      applyBatch();

      // Webhook wake (fire-and-forget) — one POST per slate with the response-shaped payloads.
      const insertedRows = interactionsByIds(db, acceptedIds);
      const bySlate = new Map<string, typeof insertedRows>();
      for (const row of insertedRows) {
        const list = bySlate.get(row.slateId) ?? [];
        list.push(row);
        bySlate.set(row.slateId, list);
      }
      for (const [slateId, interactions] of bySlate) {
        webhook.enqueue(slateId, interactions);
      }

      return jsonResponse({ accepted, duplicate }, 202);
    });
}

function asOptionalString(value: unknown): string | undefined {
  return typeof value === "string" ? value : undefined;
}

/** If-None-Match may be a comma list, quoted, weak (W/), or "*". */
export function etagMatches(ifNoneMatch: string, contentHash: string): boolean {
  return ifNoneMatch
    .split(",")
    .map((part) => part.trim())
    .some((part) => {
      const normalized = part.startsWith("W/") ? part.slice(2) : part;
      const unquoted = normalized.replace(/^"|"$/g, "");
      return part === "*" || unquoted === contentHash;
    });
}
