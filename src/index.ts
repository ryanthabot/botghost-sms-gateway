/**
 * botghost-sms-gateway — Cloudflare Worker relay (multi-tenant v2).
 *
 * Anyone can create a gateway: the Android app generates an API key locally,
 * registers only its SHA-256 hash, and gets back a device id. Each device gets
 * its own Durable Object (queue). BotGhost posts to /g/<deviceId>/outbox; the
 * phone polls /g/<deviceId>/poll. Traffic between devices is fully isolated.
 */

export { GatewayQueue } from "./gateway";

export interface Env {
  GATEWAY: DurableObjectNamespace;
  /** Optional global override for {code} messages; must contain "{code}". */
  MESSAGE_TEMPLATE?: string;
}

const DEVICE_ID_RE = /^[a-f0-9]{12}$/;
const DEFAULT_TEMPLATE = "Your verification code is {code}.";

const j = (data: unknown, status = 200) =>
  new Response(JSON.stringify(data), { status, headers: { "content-type": "application/json" } });

const err = (status: number, error: string) => j({ error }, status);

/** Best-effort per-isolate registration throttle. */
const regCounts = new Map<string, number[]>();
function allowRegistration(ip: string): boolean {
  const now = Date.now();
  const recent = (regCounts.get(ip) ?? []).filter((t) => now - t < 3_600_000);
  if (recent.length >= 5) {
    regCounts.set(ip, recent);
    return false;
  }
  recent.push(now);
  regCounts.set(ip, recent);
  return true;
}

function keyFrom(request: Request): string {
  return (
    request.headers.get("x-api-key") ??
    request.headers.get("authorization")?.replace(/^Bearer\s+/i, "") ??
    ""
  );
}

/** "+1 (555) 123-4567" -> "+15551234567"; null when not a plausible number. */
function normalizeTo(raw: unknown): string | null {
  if (typeof raw !== "string") return null;
  const s = raw.trim();
  const plus = s.startsWith("+");
  const digits = s.replace(/\D+/g, "");
  if (digits.length < 7 || digits.length > 15) return null;
  return (plus ? "+" : "") + digits;
}

function buildMessage(body: Record<string, unknown>, env: Env): string | null {
  if (typeof body.message === "string") {
    const t = body.message.trim();
    return t.length >= 1 && t.length <= 1600 ? t : null;
  }
  if (typeof body.code === "string" && /^\d{3,12}$/.test(body.code.trim())) {
    const template = env.MESSAGE_TEMPLATE?.includes("{code}") ? env.MESSAGE_TEMPLATE : DEFAULT_TEMPLATE;
    return template.replace("{code}", body.code.trim());
  }
  return null;
}

function newDeviceId(): string {
  const bytes = crypto.getRandomValues(new Uint8Array(6));
  return [...bytes].map((b) => b.toString(16).padStart(2, "0")).join("");
}

async function doOp(env: Env, deviceId: string, op: Record<string, unknown>): Promise<Response> {
  const stub = env.GATEWAY.get(env.GATEWAY.idFromName(deviceId));
  return stub.fetch("https://gateway/", { method: "POST", body: JSON.stringify(op) });
}

export default {
  async fetch(request: Request, env: Env): Promise<Response> {
    const url = new URL(request.url);
    const path = url.pathname;

    if (request.method === "GET" && path === "/health") {
      return j({ ok: true, service: "botghost-sms-gateway", version: 2 });
    }

    // Register a new gateway device (app sends only the key HASH).
    if (path === "/api/register" && request.method === "POST") {
      let body: Record<string, unknown>;
      try {
        body = (await request.json()) as Record<string, unknown>;
      } catch {
        return err(400, "body must be JSON");
      }
      const keyHash = String(body.keyHash ?? "");
      if (!/^[a-f0-9]{64}$/.test(keyHash)) return err(400, "keyHash must be the sha-256 hex of your API key");
      const name = typeof body.name === "string" ? body.name.slice(0, 64) : "";
      const ip = request.headers.get("cf-connecting-ip") ?? "unknown";
      if (!allowRegistration(ip)) return err(429, "too many registrations from this IP, try later");

      for (let attempt = 0; attempt < 3; attempt++) {
        const deviceId = newDeviceId();
        const res = await doOp(env, deviceId, { op: "init", keyHash, name });
        if (res.status === 200) return j({ deviceId }, 201);
        if (res.status !== 409) return res; // propagate validation errors
        // 48-bit id collision — try again
      }
      return err(500, "could not allocate device id");
    }

    // Per-device API: /g/<deviceId>/<action>
    const m = path.match(/^\/g\/([a-f0-9]{12})\/([a-z]+)$/);
    if (m) {
      const [, deviceId, action] = m;
      const key = keyFrom(request);

      if (action === "outbox" && request.method === "POST") {
        let body: Record<string, unknown>;
        try {
          body = (await request.json()) as Record<string, unknown>;
        } catch {
          return err(400, "body must be JSON");
        }
        const to = normalizeTo(body.to);
        if (!to) return err(400, `"to" must be a phone number (7-15 digits, optional leading +)`);
        const message = buildMessage(body, env);
        if (!message) return err(400, `provide "message" (1-1600 chars) or "code" (3-12 digits)`);
        return doOp(env, deviceId, {
          op: "enqueue", key, to, message,
          clientId: typeof body.id === "string" ? body.id : undefined,
        });
      }

      if (action === "poll" && request.method === "GET") {
        const limit = parseInt(url.searchParams.get("limit") ?? "10", 10) || 10;
        return doOp(env, deviceId, { op: "poll", key, limit });
      }

      if (action === "ack" && request.method === "POST") {
        let body: Record<string, unknown>;
        try {
          body = (await request.json()) as Record<string, unknown>;
        } catch {
          return err(400, "body must be JSON");
        }
        return doOp(env, deviceId, {
          op: "ack", key,
          id: typeof body.id === "string" ? body.id : "",
          status: typeof body.status === "string" ? body.status : "",
          error: typeof body.error === "string" ? body.error : undefined,
        });
      }

      if (action === "heartbeat" && request.method === "POST") {
        return doOp(env, deviceId, { op: "heartbeat", key });
      }

      if (action === "status" && request.method === "GET") {
        return doOp(env, deviceId, { op: "status", key });
      }

      if (action === "rotate" && request.method === "POST") {
        let body: Record<string, unknown>;
        try {
          body = (await request.json()) as Record<string, unknown>;
        } catch {
          return err(400, "body must be JSON");
        }
        return doOp(env, deviceId, { op: "rotate", key, newKeyHash: String(body.newKeyHash ?? "") });
      }

      if (action === "delete" && request.method === "DELETE") {
        return doOp(env, deviceId, { op: "destroy", key });
      }
    }

    return err(404, "not found");
  },
};
