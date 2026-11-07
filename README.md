# botghost-sms-gateway

Send SMS from your own Android phone + SIM via a webhook. Built for
[BotGhost](https://botghost.com) commands (e.g. phone verification); works with
anything that can POST JSON.

**Every user runs on their own infrastructure** — you deploy your own free
Cloudflare relay with one click, your phone app connects to it, and nobody
shares queues, keys, or quota. Setup takes about two minutes.

[![Deploy to Cloudflare](https://deploy.workers.cloudflare.com/button)](https://deploy.workers.cloudflare.com/?url=https://github.com/ryanthabot/botghost-sms-gateway)

## How it works

```
Discord /verify  →  BotGhost "Send an API Request" block
                     POST https://<your-worker>.workers.dev/g/<your-device-id>/outbox
                          X-Api-Key: <your key>    {"to": "+1555...", "code": "482913"}
                          → your Cloudflare Worker queues it in your device's storage
                          ← your phone's app polls every few seconds
                          → phone sends the SMS from its SIM → confirms
```

Each phone gets its own isolated queue and API key. The API key is generated
on the phone; the server only ever stores its SHA-256 hash.

## Setup (end user, ~2 minutes)

1. **Deploy your relay** — click the Deploy to Cloudflare button above
   (free Cloudflare account). When it finishes, copy your worker URL
   (`https://botghost-sms-gateway.<your-name>.workers.dev`).
2. **Install the app** — grab the latest APK from
   [Releases](https://github.com/ryanthabot/botghost-sms-gateway/releases)
   and install it on any Android phone with an active SIM
   (iOS can't do this — Apple forbids background SMS).
3. **Connect** — open the app, paste your worker URL, tap
   **Create my gateway**, allow SMS + notification permissions. Done — the
   gateway service starts immediately and auto-starts on boot.
4. **Copy into BotGhost** — the app shows your webhook URL and API key with
   Copy buttons. Optional sanity check first: the app's **Test it** card
   sends a real SMS to your own phone through the webhook.
5. For best reliability, exclude the app from battery optimization
   (Settings → Apps → BotGhost SMS Gateway → Battery).

## BotGhost wiring

Add a **"Send an API Request"** block (`api_action`) after your bot generates
the code:

- **Method:** POST
- **URL:** (paste from the app)
- **Headers:** `X-Api-Key` = (paste from the app)
- **Body** key/values:
  - `to` = phone number the user submitted (option/variable)
  - `code` = the generated code (or `message` = arbitrary text)

```json
{ "to": "+15551234567", "code": "482913", "id": "{user_id}-{timestamp}" }
```

`"id"` (optional) deduplicates BotGhost retries. `"code"` renders as
"Your verification code is {code}."; `"message"` sends any text (1–1600 chars).

Suggested `/verify` flow: user submits phone → bot generates 6-digit code →
API request above → bot says "check your texts" → modal/button to enter the
code → condition compares to the stored code.

## Repository layout

| Path | What |
|---|---|
| `src/`, `wrangler.jsonc`, `package.json` | the Cloudflare Worker + `GatewayQueue` Durable Object (multi-tenant relay) — this is what the Deploy button deploys |
| `android/` | "BotGhost SMS Gateway" app (Kotlin + Compose) |
| `docs/` | design docs |

Build the APK yourself: `cd android && gradlew.bat assembleDebug`.
Run the relay locally: `npm install && npm run dev` (local key in `.dev.vars`).

## Capacity & limits (per relay owner)

Free-tier Durable Objects: 100k requests/day and 100k rows written/day
([pricing](https://developers.cloudflare.com/durable-objects/platform/pricing/)).
Each phone polling every 5s ≈ 17k requests/day, so a free account supports
roughly **10–20 always-on phones** (raise poll seconds in the app to fit more).
Workers Paid ($5/mo) raises this to ~1M requests/month + pay-as-you-go —
hundreds of phones. Per-device guardrails: queue cap 50, max 10 messages/min,
max 3 delivery tries with a 10-minute claim timeout (a lost ack can duplicate
an SMS — harmless for codes).

## Security model

- The API key is generated **on the phone**; the server stores only its
  SHA-256 hash and never sees the plaintext.
- Anyone with your webhook URL **and** key can send SMS from your phone's
  SIM — share at your own discretion. "Reset API key" (Advanced settings)
  instantly revokes the old key; "Delete this gateway" wipes the server-side
  queue.
- Registration is rate-limited per IP (best effort). Device ids are 48-bit
  random; traffic between devices is fully isolated.

## API reference (per device, all authed by `X-Api-Key`)

| Method | Path | Body / Query | Notes |
|---|---|---|---|
| GET | `/health` | — | liveness, no auth |
| POST | `/api/register` | `{keyHash, name?}` | app-only; returns `{deviceId}` |
| POST | `/g/<id>/outbox` | `{to, message \| code, id?}` | enqueue → `{id}`; 429 on limits |
| GET | `/g/<id>/poll?limit=10` | — | phone claims pending messages |
| POST | `/g/<id>/ack` | `{id, status, error?}` | `failed` never retries |
| POST | `/g/<id>/heartbeat` | — | phone presence (throttled writes) |
| GET | `/g/<id>/status` | — | queue depth, heartbeat, last 10 events |
| POST | `/g/<id>/rotate` | `{newKeyHash}` | replace the API key |
| DELETE | `/g/<id>/delete` | — | wipe this gateway's queue + key |

Phone numbers normalize to digits + optional leading `+` (7–15 required).

## Known limitations

- Default SIM only (dual-SIM selection not wired). Delivery is confirmed by
  the radio (RESULT_OK per part); carrier delivery reports are not tracked.
- Carriers' spam/bulk rules still apply to each phone's own SIM — the
  per-device rate limits are guardrails, not permission.
- Future scale-up path: FCM push instead of polling (instant delivery,
  near-zero idle requests) if the free request budget ever becomes binding.

Design docs: `docs/2026-11-07-sms-gateway-design.md` (v1),
`docs/2026-11-07-sms-gateway-multitenant-v2.md` (current).
