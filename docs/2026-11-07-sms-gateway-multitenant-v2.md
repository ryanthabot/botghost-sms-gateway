# botghost-sms-gateway — v2 multi-tenant design

Date: 2026-11-07 (same day, after v1)

## Requirement change

v1 was single-user (one shared API key, one phone). v2 must let **anyone** use
it: install the APK → one tap → copy the webhook info from the app into
BotGhost → working. Mass scale, one-minute setup, no accounts.

## What changed from v1

| | v1 | v2 |
|---|---|---|
| Tenancy | one global queue (KV) | one Durable Object per device (SQLite) |
| Auth | single shared API key secret | per-device key; server stores only SHA-256 hash |
| Onboarding | user types URL + key manually | app registers itself, displays endpoint + key with Copy buttons |
| Endpoints | `/api/outbox`, `/api/poll`… | `/api/register` + `/g/<deviceId>/<action>` |
| Key management | rotate = wrangler secret | "Reset API key" in app; old key dies instantly |

## Why Durable Objects instead of KV

- KV free tier allows only 1k **writes**/day — a single 5s-polling phone with
  per-poll heartbeats would blow through it. DO free tier: 100k requests/day
  + 100k rows written/day → ~10–20 always-on phones, hundreds on Workers Paid.
- One DO instance per device gives free isolation, strong consistency (no
  read-modify-write races on whole-array keys), and real per-device rate
  limits and queues.

## Registration flow

1. App generates a 48-hex-char API key locally (`SecureRandom`).
2. App POSTs only `sha256(key)` + optional name to `/api/register`.
3. Worker allocates a 12-hex device id (48-bit random, collision-checked by
   the DO's `init`), initializes the DO with the key hash.
4. App saves device id + key, displays endpoint
   `https://…/g/<id>/outbox` and the key, auto-starts the service.
5. "Reset API key" = rotate hash server-side; "Delete this gateway" = wipe
   the DO's rows (schema kept — `deleteAll()` would break the live isolate).

Registration is throttled per-IP (best effort, per isolate). BotGhost never
learns anything about the phone beyond the device id.

## Per-device guardrails

Queue cap 50 pending; 10 enqueues/minute (counted from the device's own event
log); claim timeout 10 min; max 3 delivery tries; event log capped at 100;
heartbeat persisted at most every 5 min (rows-written budget).

## Verification (2026-11-07)

Local (`wrangler dev`): register validation, device isolation (A's poll never
sees B's messages), wrong-key 401, rate-limit 429 after cap, client-id
dedupe, rotate (old key dead immediately), destroy → clean
"gateway not initialized".

Live: same smoke cycle against production, including destroy.

Emulator (fresh install of the v2 APK): tap "Create my gateway" → registered
(device e54d6e1fc8c7), credentials card shown, service auto-started
(heartbeat on server), BotGhost-style `{"to","+15557778888","code":"606474"}`
POST → polled → SMS send RESULT_OK → acked; app showed "Sent: 1 Failed: 0".
Test device destroyed afterward; emulator stopped (a running gateway app
consumes that device's real queue).

## Scale notes

Free plan ceiling ≈ 10–20 phones polling at 5s (request-limited). Workers
Paid $5/mo → ~1M requests/month + usage → hundreds of phones. Next step if
needed: FCM push instead of polling (needs a Firebase project for the
maintainer; end users unaffected).

## v2.1 (same day): every user on their OWN Cloudflare

v2 still funneled everyone through one shared relay (the maintainer's
account). v2.1 removes the shared default entirely:

- App onboarding is now 3 steps: (1) deploy your own free relay via the
  **Deploy to Cloudflare** button (repo root holds `wrangler.jsonc` for the
  button to find — the worker moved from `worker/` to the repo root),
  (2) paste your worker URL, (3) create the gateway. No bundled relay URL.
- Distribution: public repo `ryanthabot/botghost-sms-gateway` + GitHub
  Release APK; the deploy-button URL is baked into the app's "Open the
  deploy page →" link.
- Verified by deploying a second, fully independent worker instance
  (`bg-sms-gateway-user-test`) and running the fresh-install flow against
  it: paste URL → register → BotGhost-style POST → SMS sent → acked. The
  test worker was deleted afterward.
