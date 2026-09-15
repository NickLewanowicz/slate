# Slate API — curl examples (v2)

Walkthrough of every endpoint in [`schema/api.md`](../schema/api.md) (the frozen
contract) with realistic request/response pairs. The authoritative reference is
`schema/api.md`; this file only shows it in action.

```bash
export SLATE_URL="http://localhost:3000"      # from an Android emulator: http://10.0.2.2:3000
export SLATE_API_KEY="change-me"              # the single shared SLATE_API_KEY
AUTH="Authorization: Bearer $SLATE_API_KEY"
CT="Content-Type: application/json"
```

Conventions recap: plain JSON bodies on success; **every** error uses the error
envelope; full replace only (no PATCH); timestamps ISO-8601 UTC.

---

## 1. Health + ping

```bash
curl -sS "$SLATE_URL/health"
```
```json
{ "status": "ok" }
```

```bash
curl -sS -H "$AUTH" "$SLATE_URL/api/ping"
```
```json
{ "ok": true, "serverTime": "2026-09-14T21:04:11.512Z", "version": "2.0.0" }
```

## 2. PUT /api/slates/{slateId} — create or full-replace

```bash
curl -sS -X PUT -H "$AUTH" -H "$CT" \
  --data-binary @schema/golden/status-board.json \
  "$SLATE_URL/api/slates/nightly"
```
New slate → **201**:
```json
{ "slateId": "nightly", "updatedAt": "2026-09-14T21:04:12.003Z",
  "contentHash": "9f2c1a6e…", "created": true }
```
Same call again (identical body) → **200**, `created: false`, same `contentHash`.

The `slateId` in the path must equal the `id` in the body (400 otherwise), and
`updatedAt` is server-stamped — agents should not send it.

### Validation failure → the error envelope

```bash
curl -sS -X PUT -H "$AUTH" -H "$CT" \
  --data-binary @schema/golden/unknown-element.json \
  "$SLATE_URL/api/slates/future"
```
→ **400** (the `carousel` element type and `chartreuse` tone do not exist yet):
```json
{
  "error": {
    "code": "validation_error",
    "message": "children[1].type: unknown element type",
    "hint": "type 'carousel' is not in the v2 element set (column, row, text, statusRow, todoList, question, progress, divider, spacer). If you meant a rotating list, render it as a todoList or consecutive statusRows. Unknown tones fail the same way: tone must be ok|warn|error|info|neutral.",
    "status": 400
  }
}
```
Nothing is partially applied. `hint` is written for an LLM: valid values, the
closest guess at intent, and how to fix. Read it, fix the payload, re-PUT.

## 3. GET /api/slates/{slateId}

```bash
curl -sS -H "$AUTH" "$SLATE_URL/api/slates/nightly"
```
→ **200** — the stored envelope with server-owned todo state applied (note
`garage` was checked from the device since the push):
```json
{
  "id": "nightly", "version": 2, "title": "🌙 Nightly", "tone": "warn",
  "updatedAt": "2026-09-14T21:04:12.003Z",
  "children": [
    { "type": "statusRow", "icon": "🏠", "label": "Home Assistant", "value": "ok", "tone": "ok", "detail": "checked 2m ago" },
    { "type": "todoList", "id": "bed", "items": [
      { "id": "doors", "label": "Lock doors", "checked": true },
      { "id": "garage", "label": "Close garage", "checked": true }
    ]}
  ]
}
```
Unknown id → **404** envelope (`code: "not_found"`).

## 4. GET /api/slates — list

```bash
curl -sS -H "$AUTH" "$SLATE_URL/api/slates"
```
```json
{ "slates": [
  { "slateId": "home",    "tone": "neutral", "updatedAt": "2026-09-14T20:58:01.100Z", "contentHash": "b31d…" },
  { "slateId": "nightly", "tone": "warn",    "updatedAt": "2026-09-14T21:04:12.003Z", "contentHash": "9f2c…" }
]}
```

## 5. DELETE /api/slates/{slateId}

```bash
curl -sS -X DELETE -H "$AUTH" -o /dev/null -w '%{http_code}\n' \
  "$SLATE_URL/api/slates/nightly"
```
→ **204** (cascades that slate's responses). Unknown id → **404**.

---

## 6. GET /api/device/slates — hash-diff list (device sync, step 1)

```bash
curl -sS -H "$AUTH" "$SLATE_URL/api/device/slates"
```
```json
{ "slates": [
  { "slateId": "home", "tone": "neutral", "updatedAt": "2026-09-14T20:58:01.100Z", "contentHash": "b31d…" }
]}
```
The device compares `contentHash` to what it has cached and only fetches changed slates.

## 7. GET /api/device/slates/{slateId} — ETag fetch (step 2)

```bash
curl -sS -D - -H "$AUTH" \
  -H 'If-None-Match: "b31d…"' \
  "$SLATE_URL/api/device/slates/home" -o body.json
```
Unchanged → **304**, empty body, no wasted transfer:
```
HTTP/1.1 304 Not Modified
ETag: "b31d…"
```
Changed (or no `If-None-Match`) → **200** envelope + `ETag: "<contentHash>"`.
The ETag is the content hash, so clock skew is irrelevant and an identical
re-push costs devices nothing.

## 8. (First `PUT /api/slates/home` — a slate must exist before its device posts to it.) POST /api/device/interactions — user taps land here

A batch mixing all three kinds the v2 MVP produces: an `answer` (question
button), a `check` (todo toggle), and a `text` (free-text reply, typed in the
companion app). Shape per [`schema/interactions.schema.json`](../schema/interactions.schema.json):

```bash
curl -sS -X POST -H "$AUTH" -H "$CT" \
  "$SLATE_URL/api/device/interactions" \
  --data-binary '{
    "interactions": [
      { "seq": "0b8f1e6a-1", "slateId": "home", "kind": "answer",
        "elementId": "q-backup", "questionId": "q-backup",
        "optionId": "purge", "optionLabel": "Purge",
        "clientAt": "2026-09-14T23:17:02Z" },
      { "seq": "0b8f1e6a-2", "slateId": "home", "kind": "check",
        "elementId": "bed", "itemId": "garage",
        "clientAt": "2026-09-14T23:17:09Z" },
      { "seq": "0b8f1e6a-3", "slateId": "home", "kind": "text",
        "elementId": "q-backup", "questionId": "q-backup",
        "value": "actually purge only snapshots older than 30 days",
        "clientAt": "2026-09-14T23:18:40Z" }
    ]
  }'
```
→ **202**:
```json
{ "accepted": 3, "duplicate": 0 }
```
All-or-nothing: one invalid interaction → **400** envelope and **none** are
applied. Side effects per accepted interaction: recorded for polling,
`check`/`uncheck` mutate the stored spec's todo item, webhook wake fires.

Re-sending the same `seq` values (offline queue replay) → `accepted: 0,
duplicate: 3` — dedupe is by `seq`, never re-USE a `seq` after a 2xx.

`uncheck` looks like `check` with `kind: "uncheck"`; `tap` is the generic
kind (elementId only) for non-semantic taps.


> **Cursor invariants (all three matter):**
> - `since` is **exclusive** — returns interactions with `id > since` (pass `0` for everything).
> - `beforeId` on ack is **inclusive** — deletes interactions with `id <= beforeId` (pass the cursor you just processed; nothing you saw is re-delivered).
> - Interaction `id`s are ONE global sequence across all slates — never assume per-slate numbering.
> - `seq` dedupe is scoped per-slate: the same `seq` on a different slate is a fresh interaction.

## 9. GET /api/slates/{slateId}/responses — the agent polls

```bash
curl -sS -H "$AUTH" "$SLATE_URL/api/slates/home/responses?since=0"
```
```json
{
  "responses": [
    { "id": 17, "slateId": "home", "kind": "answer",
      "elementId": "q-backup", "questionId": "q-backup",
      "optionId": "purge", "optionLabel": "Purge",
      "itemId": null, "value": null,
      "clientAt": "2026-09-14T23:17:02Z", "stale": false,
      "createdAt": "2026-09-14T23:17:03.220Z" },
    { "id": 18, "slateId": "home", "kind": "check",
      "elementId": "bed", "questionId": null,
      "optionId": null, "optionLabel": null, "itemId": "garage",
      "value": null, "clientAt": "2026-09-14T23:17:09Z", "stale": false,
      "createdAt": "2026-09-14T23:17:09.871Z" }
  ],
  "cursor": 3,
  "hasMore": false
}
```
Ascending by `id`; `cursor` = last id (feed it back as `?since=3` next poll);
`limit` default 50 max 200; `stale: true` if the tap landed after the
question's `expiresAt` (answer is still delivered, just flagged).

## 10. DELETE /api/slates/{slateId}/responses — ack

```bash
curl -sS -X DELETE -H "$AUTH" "$SLATE_URL/api/slates/home/responses?beforeId=18"
```
```json
{ "deleted": 2 }
```
Ack **after** processing. Omit `?beforeId` to wipe all responses for the slate.
Retention also auto-prunes: last 200 interactions per slate (`SLATE_MAX_RESPONSES_PER_SLATE`),
168 h TTL (`SLATE_RESPONSE_TTL_HOURS`).

---

## 11. Webhook wake payload + signature verification

> **Note:** the wake header `X-Slate-Signature` is sent ONLY when `SLATE_WEBHOOK_SECRET` is set on the backend — verify absence means fail closed. You cannot fire a live wake without server-side env access; use the test vector below to validate your verifier offline.

With `SLATE_WEBHOOK_URL` configured, the server POSTs this on every
non-duplicate interaction (body = interactions in the same shape as responses):

```json
POST /hooks/slate-wake  HTTP/1.1
X-Slate-Signature: sha256=7d1a…e9
Content-Type: application/json

{ "type": "interactions", "slateId": "home", "interactions": [
  { "id": 19, "slateId": "home", "kind": "answer", "questionId": "q-backup",
    "optionId": "purge", "optionLabel": "Purge", "clientAt": "…",
    "createdAt": "…", "stale": false }
]}
```

Verify before trusting (delivery is fire-and-forget with 3 retries; polling
stays the source of truth — on a lost webhook the next poll still sees everything):

```bash
# $BODY = raw request body, $SLATE_WEBHOOK_SECRET = shared secret
EXPECTED="sha256=$(printf '%s' "$BODY" | openssl dgst -sha256 -hmac "$SLATE_WEBHOOK_SECRET" -r | cut -d' ' -f1)"
if [ "$EXPECTED" = "$SIGNATURE_HEADER" ]; then
  echo "authentic — wake the agent to run: slate.sh responses home"
fi
```

Timing-unsafe compare is fine here (home LAN, low stakes), but constant-time
compare is available in most languages (`hmac.compare_digest` in Python,
`crypto.timingSafeEqual` in Node) if you prefer.

## Quick reference

| # | Method + path | Auth | Success | Typical failure |
|---|---|---|---|---|
| 1 | `GET /health`, `GET /api/ping` | no / yes | 200 | — |
| 2 | `PUT /api/slates/{id}` | yes | 201 / 200 | 400 validation (read `hint`) |
| 3 | `GET /api/slates/{id}` | yes | 200 | 404 |
| 4 | `GET /api/slates` | yes | 200 | — |
| 5 | `DELETE /api/slates/{id}` | yes | 204 | 404 |
| 6 | `GET /api/device/slates` | yes | 200 | — |
| 7 | `GET /api/device/slates/{id}` + If-None-Match | yes | 200 / 304 | 404 |
| 8 | `POST /api/device/interactions` | yes | 202 | 400 (batch rejected whole) |
| 9 | `GET /api/slates/{id}/responses?since=&limit=` | yes | 200 | — |
| 10 | `DELETE /api/slates/{id}/responses?beforeId=` | yes | 200 | — |
