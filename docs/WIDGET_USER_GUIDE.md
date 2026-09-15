# Slate — user guide (the phone side)

Slate is the home-screen surface where your AI agent reports status and asks
you questions, and where you answer in one tap. The agent pushes a small card
(a *slate*); the widget renders it; your taps flow back to the agent.

## 1. Install

1. Get the APK (CI artifact or a release from the Gitea repo) and sideload it.
   Android will ask to allow installs from your browser/files app — one-time.
2. Requires Android 12L (API 31) or newer.
3. Open the app once before adding the widget.

## 2. Setup screen

First launch asks for two things:

- **Server URL** — where the backend runs, e.g. `http://slate.example.com:3000`.
  Same network as the backend (home LAN or VPN); there is no cloud relay.
- **API key** — the shared `SLATE_API_KEY` the backend was started with.

Tap **Test** — it pings the server and should come back "OK" instantly. Then
save. The key lives on-device (DataStore); traffic is plain HTTP because it is
your LAN — keep it that way or terminate TLS in front of the container.

## 3. Add the widget

Long-press the home screen → Widgets → **Slate**. Three sizes; all bind to the
slate named `home` (the per-widget picker comes later):

| Size | Shows |
|---|---|
| **Compact** (2×2-ish) | Title + tone stripe, the top few rows, and — if there is a question — its first options. Answering is always available from the app. |
| **Medium** (4×2) | Title, status rows with values and detail lines, todo list, question with up to all options. |
| **Full** (4×3+) | Everything the agent pushed: weather/progress rows, dividers, the works. Long lists scroll. |

Refresh is pull-based: periodic (every 15 min), on opening the app, after you
tap something, and whenever you open the app you can pull-to-refresh. The
**freshness dot** tells you how current the widget is — see §6.

## 4. Answering

**From the widget (one tap).** Questions show as buttons. Tap one:

- haptic tick, the chosen button becomes **"✓ <your answer>"** immediately (optimistic) and the others fade,
- the tap is queued and delivered to the server (directly, or later if you
  were offline — queued taps are kept durably),
- the agent picks your answer up on its next poll.

**Todo rows** toggle in place instantly; the check is queued the same way. The
server is the source of truth — if the agent re-pushes the list, your checks
survive because the device reconciles by pulling.

**From the app.** Open the companion app for the full picture: slate list →
slate detail. You get the same content plus:
- question answering with the same one-tap ritual, plus a "✓ Sent to agent: <answer>" confirmation,
- **free text** — questions with *free reply* enabled show a reply field
  (in the widget this is a "reply in app" row that deep-links here; widgets
  cannot host text fields),
- response status per answer: **Queued → Sent → Delivered to agent**.
  "Delivered" only appears after the agent actually polled — no silent voids.

## 5. Free text

When the agent needs words instead of a canned choice it marks the question
"free reply". Tap the "reply in app" row on the widget → the app opens on that
question → type and send. It travels the same pipeline (`kind: text`) and the
agent reads it on the next poll, exactly like a button answer.

## 6. Freshness dot & staleness

The dot next to the timestamp says how trustworthy the content is:

| Dot | Meaning |
|---|---|
The dot is a **traffic light for the whole board**, not for freshness — freshness is the "updated Xm ago" text next to it.

| **Dot color** | Meaning |
|---|---|
| **Green** | Everything on the board is ok. |
| **Amber** | Something on the board needs attention soon (warn), or the board is new since your last look. |
| **Red** | Something on the board failed or needs you now (error). |
| **Grey** | The board went stale (past its expiry) or went offline at last attempt — content is cached and will update when the connection returns. |

Questions can also carry an expiry set by the agent (`expiresAt`): after that
instant the question greys out with a **stale** badge. Your tap is still
delivered and the agent sees it flagged as late — answer anyway if it matters.

## 7. Troubleshooting

| Symptom | Fix |
|---|---|
| Setup "Test" fails | URL typo (include the port), wrong key, or phone and backend on different networks. Try the URL in the phone's browser: you should get `{"status":"ok"}` at `/health` (no auth needed). |
| Widget shows old content, dot amber/grey | Pull-to-refresh in the app; check the backend is up (`/health`). Battery optimization killing sync? Exempt the app. |
| Tap says "Queued" forever | The device can't reach the server right now — it will retry automatically; the queue survives reboot. Check Wi-Fi/VPN. |
| Todo check bounced back | The agent re-pushed the list from its own copy before your tap synced; your tap won that race only if it reached the server first. Rare; just toggle again. |
| Wrong content on one widget | Widgets bind to slate `home`. The agent may have pushed to another slate id — ask it to `push` to `home`. |
| `[unsupported: x]` in the widget | The agent sent an element type your app version doesn't know. It degrades to a caption instead of breaking — update the app when a new version ships. |

Empty state, when your agents have nothing to say: *"All quiet. Your agents
have nothing to report — enjoy it."*
