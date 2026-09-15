# Architecture

One declarative JSON contract, three small implementations.

```
┌──────────────┐  PUT /api/slates/{id}        ┌───────────────────┐
│  AI agent    │ ───────────────────────────▶ │  Backend           │
│ (OpenClaw,   │  GET  …/responses?since      │  Bun · Elysia      │
│  Hermes, …)  │ ◀─────────────────────────── │  SQLite (WAL)      │
└──────────────┘  poll answers · ack          └─────────┬─────────┘
        ▲                               ETag / 304      │  webhook wake
        │ answers (poll or wake)                        │  (HMAC-signed)
        │                                               ▼
┌───────┴───────────────────────────────────────────────────────┐
│  Android phone                                                │
│  ┌─────────────────────┐          ┌─────────────────────────┐ │
│  │ Home-screen widget   │  cache   │ Companion app (Compose) │ │
│  │ RemoteViews, 3 sizes │◀────────▶│ · setup / pairing       │ │
│  │ one-tap answers      │ DataStore│ · detail + free text    │ │
│  └─────────────────────┘          └─────────────────────────┘ │
└───────────────────────────────────────────────────────────────┘
```

## Repositories & modules

| Path | What | Notes |
|---|---|---|
| `backend/` | Bun + Elysia + `bun:sqlite` API server | Numbered SQL migrations, content-hash ETags, HMAC webhook wake, retention |
| `android/` | Kotlin app: widget + Compose companion | minSdk 31; RemoteViews widget (not Glance), Ktor client, WorkManager sync, DataStore |
| `schema/` | **The frozen contract** | JSON Schemas + `api.md` + golden fixtures consumed by tests on both sides |
| `skill/slate/` | OpenClaw skill | `SKILL.md` for LLM consumption + `slate.sh` CLI |
| `docs/site/` | Documentation site | Plain HTML/CSS, deployed to GitHub Pages |

## Core decisions (and why)

- **Strict backend, lenient renderer.** The API rejects unknown elements/props/tones
  with an actionable `hint` so agents catch typos at push time. Devices, however,
  degrade unknown content to a caption — old apps never crash on new specs.
- **The renderer owns beauty.** Agents declare content and semantic `tone` — never
  colors or geometry. Nine element types; three size buckets (compact ≤230dp /
  medium ≤400dp / full) with a render plan each.
- **Server owns mutable state.** Todo `checked` lives in the stored spec; the
  device toggles optimistically and queues `check`/`uncheck` with a `seq`
  idempotency key. No multi-writer merge.
- **Content-hash ETags.** `updatedAt` is server-owned and excluded from the hash,
  so identical re-pushes are free 304s and stale timestamps can't poison sync.
- **RemoteViews, not Glance.** The widget needs precise control at three sizes;
  per-element renderer classes feed a dispatch registry. The app previews the
  exact widget output via `RemoteViews.apply()`.
- **No FCM.** Self-hosted means no Google infra in the loop: WorkManager periodic
  sync (15 min default, 20 s fast-poll while the app is foregrounded), offline
  cache rendering, durable retry queues. The freshness dot makes staleness legible
  instead of hiding it.
- **Poll-first agent return path.** Crons are single-turn and can't receive
  webhooks; the poll loop (`responses?since=cursor` → ack `beforeId`) is the
  contract. An HMAC-signed webhook wake is optional latency reduction.

## Key source maps

| Area | Backend | Android |
|---|---|---|
| Contract validation | `backend/src/validation.ts` (Ajv + structural caps) | `spec/` (kotlinx DTOs, lenient enums, unknown-element fallback) |
| Persistence | `backend/src/store.ts`, `migrations/` | `data/` (DataStore: settings, cache, queue) |
| Interactions | `backend/src/routes/device.ts` | `interact/` (queue, optimistic state, flush) |
| Push/sync | — | `sync/` (WorkManager), `widget/` (renderers, buckets) |
| Agent return | `backend/src/routes/responses.ts`, `webhook.ts` | — |
