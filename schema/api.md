# Slate v2 API Contract (FROZEN for MVP)

All paths under `/api` require `Authorization: Bearer <SLATE_API_KEY>`. `/health` and `/` are unauthenticated.
Base URL in dev: `http://<host>:3000`. From an Android emulator, the host machine is `10.0.2.2`.

## Conventions

- **Success envelope:** plain JSON resource bodies (no wrapper).
- **Error envelope (every error, all statuses):**
  ```json
  { "error": { "code": "validation_error", "message": "children[3].tone: invalid value", "hint": "tone must be one of ok|warn|error|info|neutral. For orange-ish emphasis use \"warn\".", "status": 400 } }
  ```
  `hint` is written for an LLM: state the valid values, guess the closest intent, show a corrected snippet. Never empty.
- **Content type:** `application/json`. Server timestamps are ISO-8601 UTC.
- **Validation:** specs are validated against `schema/slate.schema.json` (compiled at boot). Full replace only — there is **no PATCH**; agents re-PUT the whole envelope.
- **Unknown fields in valid elements are rejected** (schema has `additionalProperties: false`) with a hint naming the offending path — this catches agent typos early.

## Agent endpoints

| Method | Path | Body / Query | Success | Notes |
|---|---|---|---|---|
| PUT | `/api/slates/{slateId}` | spec envelope | 201 new / 200 updated — `{slateId, updatedAt, contentHash, created}` | `slateId` in path must match `id` in body (400 otherwise). Server stamps `updatedAt`, computes `contentHash` (sha256 of canonical JSON). Full replace. |
| GET | `/api/slates/{slateId}` | — | 200 envelope / 404 | Returns stored envelope with server-owned todo `checked` state applied. |
| DELETE | `/api/slates/{slateId}` | — | 204 / 404 | Cascades interactions. |
| GET | `/api/slates` | — | 200 `{slates: [{slateId, tone, updatedAt, contentHash}]}` | |
**Polling invariants:** `since` is exclusive (returns `id > since`; `0` = from the start). Ack `beforeId` is **inclusive** (`id <= beforeId`). Interaction ids are one global sequence across slates. `seq` dedupe is per-slate.

| GET | `/api/slates/{slateId}/responses` | `?since=<id>&limit=<n>` | 200 `{responses: [{id, slateId, kind, elementId, questionId, optionId, optionLabel, itemId, value, clientAt, stale, createdAt}], cursor, hasMore}` | Cursor = last interaction id. `limit` default 50, max 200. Ascending by id. `stale` true if interaction arrived after the question's `expiresAt`. |
| DELETE | `/api/slates/{slateId}/responses` | `?beforeId=<id>` | 200 `{deleted: n}` | Ack. Omitting `beforeId` deletes all for the slate. |

## Device endpoints

| Method | Path | Body / Headers | Success | Notes |
|---|---|---|---|---|
| GET | `/api/device/slates` | — | 200 `{slates: [{slateId, tone, updatedAt, contentHash}]}` | Cheap list for hash-diff sync. |
| GET | `/api/device/slates/{slateId}` | `If-None-Match: "<contentHash>"` | 200 envelope + `ETag: "<contentHash>"`, or **304** empty | Content-hash ETag — immune to clock skew; identical re-push = free 304. |
| POST | `/api/device/interactions` | `{interactions: [...]}` per `schema/interactions.schema.json` | 202 `{accepted: n, duplicate: m}` | All-or-nothing transaction: any invalid interaction → 400, none applied. Dedupe on `seq`. Side effects: (1) record each interaction; (2) apply `check`/`uncheck` to the stored spec's todo item; (3) fire webhook wake. |
| GET | `/api/ping` | — | 200 `{ok: true, serverTime, version}` | App "Test connection". |

## Health

`GET /health` → 200 `{status: "ok"}` (unauthenticated; Docker HEALTHCHECK hits this).

## Webhook wake

When `SLATE_WEBHOOK_URL` is set, on every non-duplicate interaction insert the server POSTs:

```json
{ "type": "interactions", "slateId": "home", "interactions": [ ...same shape as responses... ] }
```

Headers: `X-Slate-Signature: sha256=<hex hmac-sha256(body, SLATE_WEBHOOK_SECRET)>` when the secret is set. Delivery is fire-and-forget with 3 retries (2s/8s/30s); failures never fail the client's 202. This is the OpenClaw gateway-wake path; polling remains the source of truth.

## Server-side behavior

- **Todo state:** server owns canonical `checked`. `check`/`uncheck` interactions mutate the stored spec JSON. Devices reconcile by pulling.
- **Retention:** keep last `SLATE_MAX_RESPONSES_PER_SLATE` (default 200) interactions per slate, TTL `SLATE_RESPONSE_TTL_HOURS` (default 168). Enforced on insert + a 10-minute sweep.
- **Spec caps:** as per JSON Schema (depth 6, 60 elements, 64 KB serialized). Oversized → 400 with hint.
- **Env:** `SLATE_PORT` (3000), `SLATE_DB_PATH` (`./data/slate.db`), `SLATE_API_KEY` (required — boot fails without it), `SLATE_WEBHOOK_URL`, `SLATE_WEBHOOK_SECRET`, `SLATE_MAX_RESPONSES_PER_SLATE` (200), `SLATE_RESPONSE_TTL_HOURS` (168), `SLATE_LOG_LEVEL` (info).
