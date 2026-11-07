# botghost-sms-gateway — Design

Date: 2026-11-07

## Goal

Let a BotGhost command send an SMS (e.g. a verification code) through the user's own
Android phone + SIM. BotGhost calls a webhook; the phone sends the text and reports back.

Scope: the relay API + the Android app only. The BotGhost command itself is built later
by the user (block config is documented in the README).

## Flow

```
Discord /verify (user submits phone number)
  → BotGhost "Send an API Request" block
  → POST https://<worker>.workers.dev/api/outbox   (X-Api-Key)
       body: {"to": "+1555...", "code": "482913"}   (or {"to", "message"})
  → Cloudflare Worker queues the message in KV
  ← Android app (foreground service) polls GET /api/poll every N seconds
  → app sends the SMS via SmsManager (the phone's SIM)
  → app POSTs /api/ack  {id, status: sent|failed, error?}
```

## Why this architecture

- **Relay (Cloudflare Worker + KV), not phone-hosted HTTP.** BotGhost's servers need a
  public HTTPS URL. A phone on mobile data/home Wi-Fi is not reachable inbound, and a
  tunnel (ngrok/cloudflared on a PC) dies whenever the PC is off. A Worker is free,
  always-on, and gives a stable URL. The phone only makes *outbound* polls, which works
  on any network.
- **Polling (~5s), not FCM push.** No Firebase project, no google-services.json, works
  on any Android. Verification codes tolerate a few seconds of latency. FCM is a
  documented future upgrade.
- **Android-only.** iOS does not allow apps to send SMS in the background at all — the
  gateway phone must be Android (any spare phone with an active SIM).
- **KV read-modify-write of whole queues** is fine here: one producer (BotGhost), one
  consumer (one phone). Not safe for many concurrent devices — out of scope.

## Components

### 1. `worker/` — Cloudflare Worker (relay + queue + status)

Endpoints (all under `/api/*` require header `X-Api-Key` unless noted):

| Method | Path          | Purpose |
|--------|---------------|---------|
| GET    | `/health`     | liveness, no auth |
| POST   | `/api/outbox` | BotGhost enqueues `{to, message \| code, id?}` → `{id}` |
| GET    | `/api/poll`   | phone claims up to N pending messages |
| POST   | `/api/ack`    | phone reports `{id, status, error?}` |
| POST   | `/api/heartbeat` | phone presence ping `{device?}` |
| GET    | `/api/status` | queue depth + last heartbeat + recent events |

KV keys: `pending`, `inflight`, `events` (capped 100), `heartbeat`.

Semantics:
- Claims move a message pending → inflight with a 10-minute claim timeout; if no ack
  arrives it is re-queued (max 3 tries), then dropped with an event. At-least-once
  delivery: a lost ack can duplicate an SMS (harmless for codes).
- `ack failed` never retries (bad number etc.).
- Queue capped at 50 pending → 429 (protects the SIM from runaway bots).
- `to` normalized to digits with optional leading `+`; 7–15 digits required.
- `message` (1–1600 chars) or `code` (3–12 digits rendered through
  `MESSAGE_TEMPLATE`, default "Your verification code is {code}.").
- Optional client `id` (BotGhost retries) deduplicated.
- Secret: `API_KEY` (prod: `wrangler secret put`; dev: `.dev.vars`).

### 2. `android/` — "BotGhost SMS Gateway" app (Kotlin + Compose)

- **GatewayService** — foreground service (`dataSync` type): poll → send → ack loop,
  START_STICKY, status notification with Stop action, restarts on boot (toggleable).
- **SmsSender** — `SmsManager` with per-part sent PendingIntents, 25s confirmation
  timeout, multipart for long text.
- **GatewayApi** — HttpURLConnection client for the five endpoints (no extra deps).
- **Settings** — server URL, API key, poll seconds (default 5, min 3), max sends/min
  (default 10), dry-run (test wiring without sending), auto-start on boot, keep awake
  (partial wakelock). Stored in SharedPreferences.
- **EventLog** — in-app log (sent/failed/error) persisted, capped 200 entries.
- Permissions: `SEND_SMS`, `INTERNET`, `POST_NOTIFICATIONS`, `FOREGROUND_SERVICE*`,
  `RECEIVE_BOOT_COMPLETED`, `WAKE_LOCK`. Cleartext HTTP allowed (local/emulator
  testing); the production worker URL is HTTPS anyway.
- minSdk 26, targetSdk 34 (34 keeps boot-time FGS start allowed; 35 restricts it).

## Security

- Single shared API key (rotate = one `wrangler secret put`; update app + BotGhost).
  Store it in BotGhost's Secrets rail, never in a command body.
- The key lets the holder send SMS from the user's SIM → default rate limits, queue
  cap, and guidance to keep the key private.
- This is a personal self-hosted gateway over the user's own SIM, single-purpose.

## Testing

1. Worker locally: `wrangler dev` + curl — auth failure, outbox validation, full
   poll→ack cycle, requeue/drop paths, status output.
2. App on the API 34 emulator against `http://10.0.2.2:8787` (wrangler dev) — real
   end-to-end: enqueue via curl, app polls, SmsManager path exercised, ack empties
   the queue. Dry-run mode as fallback if the emulator radio rejects the send.
3. Production: after deploy, same curl cycle against the live URL.

## Limitations / non-goals

- iOS, multi-device fan-out, delivery reports (sent-confirmation only), dual-SIM
  selection (uses default SIM), incoming-SMS handling.
- KV is eventually consistent; a poll right after an enqueue may miss it once —
  next poll (seconds later) picks it up.
- Carrier spam rules still apply to the SIM; defaults (10/min, queue cap) are guardrails.
