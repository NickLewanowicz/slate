import { Elysia } from "elysia";
import type { Database } from "bun:sqlite";
import type { AppConfig } from "../config";
import { badRequest, jsonResponse, notFound, payloadTooLarge, validationError } from "../errors";
import { contentLengthTooLarge, isPlainObject, parseJsonBody } from "../http";
import { MAX_SPEC_BYTES, deleteSlate, getSlate, listSlates, putSlate, specSizeBytes } from "../store";
import { checkStructuralCaps, type SlateValidator } from "../validation";

/** Hard cap on request bodies we are willing to buffer (4× the serialized-spec cap). */
export const MAX_BODY_BYTES = 256 * 1024;

export const NOT_FOUND_SLATE_HINT = (slateId: string) =>
  `No slate with id "${slateId}" exists. Push one first: PUT /api/slates/${slateId} with a spec envelope {"id":"${slateId}","version":2,"children":[...]}, or GET /api/slates for the list of existing slates.`;

export function createSlateRoutes(deps: { db: Database; config: AppConfig; validator: SlateValidator }) {
  const { db, config, validator } = deps;
  return new Elysia({ name: "slate-slates" })
    .put(
      "/api/slates/:slateId",
      async ({ params, request }) => {
        const slateId = params["slateId"];
        if (contentLengthTooLarge(request, MAX_BODY_BYTES)) {
          return payloadTooLarge(
            `Request body exceeds the ${MAX_BODY_BYTES / 1024} KB transport limit.`,
            `Bodies larger than ${MAX_BODY_BYTES / 1024} KB are rejected without reading. A valid slate is at most ${MAX_SPEC_BYTES / 1024} KB serialized — you are sending far more than any slate can hold. Trim the payload before retrying.`,
          );
        }
        const parsed = await parseJsonBody(request);
        if (!parsed.ok) {
          return badRequest(
            "invalid_json",
            "Request body is not valid JSON.",
            `The spec must be a single JSON object. The server received: ${(parsed.raw ?? "(empty body)").slice(0, 120)}. Send Content-Type: application/json and a complete envelope, e.g. {"id":"${slateId}","version":2,"title":"...","children":[{"type":"text","text":"hello"}]}`,
          );
        }
        const body = parsed.body;
        if (isPlainObject(body) && body["id"] !== undefined && body["id"] !== slateId) {
          return badRequest(
            "id_mismatch",
            `Path slateId "${slateId}" does not match body id ${JSON.stringify(body["id"])}.`,
            `The slate id in the URL path must equal the "id" field in the body (body says ${JSON.stringify(body["id"])}). Fix the body to include "id":"${slateId}" (or PUT to /api/slates/${String(body["id"])} instead). Corrected snippet: {"id":"${slateId}","version":2,"children":[...]}`,
          );
        }
        if (specSizeBytes(body) > MAX_SPEC_BYTES) {
          return payloadTooLarge(
            `Serialized spec exceeds the ${MAX_SPEC_BYTES / 1024} KB limit.`,
            `Specs are capped at ${MAX_SPEC_BYTES / 1024} KB serialized (widget surfaces are small). Shrink the payload: split across slates, shorten text (maxLength 500/char), or reduce children (max 12 per container). Current size: ${specSizeBytes(body)} bytes.`,
          );
        }
        const caps = checkStructuralCaps(body);
        if (!caps.ok) {
          return badRequest("caps_exceeded", caps.message, caps.hint);
        }
        const result = validator.validateSpec(body);
        if (!result.ok) {
          return validationError(result.errors.message, result.errors.hint);
        }
        const envelope = isPlainObject(body) ? body : {};
        const put = putSlate(db, slateId, envelope);
        return jsonResponse(
          { slateId: put.slateId, updatedAt: put.updatedAt, contentHash: put.contentHash, created: put.created },
          put.created ? 201 : 200,
        );
      },
    )
    .get("/api/slates", () => jsonResponse({ slates: listSlates(db) }))
    .get("/api/slates/:slateId", ({ params }) => {
      const row = getSlate(db, params["slateId"]);
      if (!row) return notFound(`Slate "${params["slateId"]}" not found.`, NOT_FOUND_SLATE_HINT(params["slateId"]));
      return jsonResponse(JSON.parse(row.spec_json));
    })
    .delete("/api/slates/:slateId", ({ params }) => {
      const slateId = params["slateId"];
      const deleted = db.transaction(() => deleteSlate(db, slateId))();
      if (!deleted) return notFound(`Slate "${slateId}" not found.`, NOT_FOUND_SLATE_HINT(slateId));
      return new Response(null, { status: 204 }); // interactions cascade via FK
    });
}
