/**
 * One Durable Object instance per gateway device (keyed by deviceId).
 * Holds the device's message queue, event log, and key hash in SQLite.
 * The API key itself never leaves the phone — only its SHA-256 hash is stored.
 */

type DOState = DurableObjectState;

export interface QueueRow extends Record<string, SqlStorageValue> {
  id: string;
  client_id: string | null;
  to_num: string;
  message: string;
  created_at: number;
  claim_until: number;
  tries: number;
}

interface EventRow extends Record<string, SqlStorageValue> {
  ts: number;
  kind: string;
  detail: string;
}

const MAX_PENDING = 50;
const MAX_ENQUEUE_PER_MIN = 10;
const CLAIM_TIMEOUT_MS = 10 * 60 * 1000;
const MAX_TRIES = 3;
const EVENTS_CAP = 100;
const HEARTBEAT_WRITE_INTERVAL_MS = 5 * 60 * 1000;

const j = (data: unknown, status = 200) =>
  new Response(JSON.stringify(data), { status, headers: { "content-type": "application/json" } });

async function sha256Hex(s: string): Promise<string> {
  const buf = await crypto.subtle.digest("SHA-256", new TextEncoder().encode(s));
  return [...new Uint8Array(buf)].map((b) => b.toString(16).padStart(2, "0")).join("");
}

function safeEqual(a: string, b: string): boolean {
  if (a.length !== b.length) return false;
  let d = 0;
  for (let i = 0; i < a.length; i++) d |= a.charCodeAt(i) ^ b.charCodeAt(i);
  return d === 0;
}

export class GatewayQueue {
  private sql: DOState["storage"]["sql"];

  constructor(private ctx: DOState) {
    this.sql = ctx.storage.sql;
    this.sql.exec("CREATE TABLE IF NOT EXISTS meta (k TEXT PRIMARY KEY, v TEXT)");
    this.sql.exec(`CREATE TABLE IF NOT EXISTS messages (
      id TEXT PRIMARY KEY, client_id TEXT, to_num TEXT, message TEXT,
      created_at INTEGER, claim_until INTEGER DEFAULT 0, tries INTEGER DEFAULT 0)`);
    this.sql.exec("CREATE INDEX IF NOT EXISTS idx_messages_claim ON messages (claim_until)");
    this.sql.exec("CREATE TABLE IF NOT EXISTS events (ts INTEGER, kind TEXT, detail TEXT)");
  }

  async fetch(request: Request): Promise<Response> {
    let op: Record<string, unknown>;
    try {
      op = (await request.json()) as Record<string, unknown>;
    } catch {
      return j({ error: "bad request body" }, 400);
    }
    try {
      switch (op.op) {
        case "init": return await this.init(op);
        case "enqueue": return await this.enqueue(op);
        case "poll": return await this.poll(op);
        case "ack": return await this.ack(op);
        case "heartbeat": return await this.heartbeat(op);
        case "status": return await this.status(op);
        case "rotate": return await this.rotate(op);
        case "destroy": return await this.destroy(op);
        default: return j({ error: "unknown op" }, 400);
      }
    } catch (e) {
      return j({ error: `internal: ${String(e)}` }, 500);
    }
  }

  // --- meta helpers ---

  private meta(key: string): string | null {
    const row = this.sql.exec<{ v: string }>("SELECT v FROM meta WHERE k = ?", key).toArray()[0];
    return row?.v ?? null;
  }

  private setMeta(key: string, value: string): void {
    this.sql.exec("INSERT INTO meta (k, v) VALUES (?, ?) ON CONFLICT(k) DO UPDATE SET v = excluded.v", key, value);
  }

  private async auth(key: unknown): Promise<Response | null> {
    if (typeof key !== "string" || !key) return j({ error: "invalid or missing API key (X-Api-Key header)" }, 401);
    const stored = this.meta("key_hash");
    if (!stored) return j({ error: "gateway not initialized" }, 404);
    if (!safeEqual(await sha256Hex(key), stored)) return j({ error: "invalid or missing API key (X-Api-Key header)" }, 401);
    return null;
  }

  private addEvent(kind: string, detail: string): void {
    this.sql.exec("INSERT INTO events (ts, kind, detail) VALUES (?, ?, ?)", Date.now(), kind, detail);
    this.sql.exec(
      `DELETE FROM events WHERE ts < (SELECT MIN(ts) FROM (SELECT ts FROM events ORDER BY ts DESC LIMIT ${EVENTS_CAP}))`
    );
  }

  // --- ops ---

  private async init(op: Record<string, unknown>): Promise<Response> {
    const keyHash = String(op.keyHash ?? "");
    if (!/^[a-f0-9]{64}$/.test(keyHash)) return j({ error: "keyHash must be sha-256 hex" }, 400);
    const name = typeof op.name === "string" ? op.name.slice(0, 64) : "";
    const existing = this.meta("key_hash");
    if (existing && !safeEqual(existing, keyHash)) return j({ error: "device id already taken" }, 409);
    if (!existing) {
      this.setMeta("key_hash", keyHash);
      this.setMeta("name", name);
      this.setMeta("created", String(Date.now()));
      this.addEvent("created", name ? `name=${name}` : "");
    }
    return j({ ok: true });
  }

  private async enqueue(op: Record<string, unknown>): Promise<Response> {
    const denied = await this.auth(op.key);
    if (denied) return denied;
    const to = String(op.to ?? "");
    const message = String(op.message ?? "");
    const clientId = typeof op.clientId === "string" && op.clientId.trim() ? op.clientId.trim().slice(0, 100) : null;

    const now = Date.now();
    const recent = this.sql.exec<{ n: number }>(
      "SELECT COUNT(*) AS n FROM events WHERE kind = 'queued' AND ts > ?", now - 60_000
    ).toArray()[0]?.n ?? 0;
    if (recent >= MAX_ENQUEUE_PER_MIN) {
      this.addEvent("rejected", `rate limit: ${recent} enqueued in the last minute`);
      return j({ error: `rate limit: max ${MAX_ENQUEUE_PER_MIN} messages per minute` }, 429);
    }
    const pending = this.sql.exec<{ n: number }>("SELECT COUNT(*) AS n FROM messages").toArray()[0]?.n ?? 0;
    if (pending >= MAX_PENDING) {
      this.addEvent("rejected", `queue full (${pending})`);
      return j({ error: `queue full (${pending} pending)` }, 429);
    }
    if (clientId) {
      const dupe = this.sql.exec("SELECT id FROM messages WHERE client_id = ?", clientId).toArray()[0];
      if (dupe) return j({ deduped: true }, 202);
    }
    const id = crypto.randomUUID();
    this.sql.exec(
      "INSERT INTO messages (id, client_id, to_num, message, created_at, claim_until, tries) VALUES (?, ?, ?, ?, ?, 0, 0)",
      id, clientId, to, message, now
    );
    this.addEvent("queued", `id=${id} to=${to}`);
    return j({ id }, 202);
  }

  /** Requeue claimed-but-unacked messages (phone died mid-send). */
  private sweep(): void {
    const now = Date.now();
    const expired = this.sql.exec<QueueRow>(
      "SELECT * FROM messages WHERE claim_until != 0 AND claim_until < ?", now
    ).toArray();
    for (const m of expired) {
      if (m.tries < MAX_TRIES) {
        this.sql.exec("UPDATE messages SET claim_until = 0, tries = ? WHERE id = ?", m.tries + 1, m.id);
        this.addEvent("requeued", `id=${m.id} to=${m.to_num} tries=${m.tries + 1}`);
      } else {
        this.sql.exec("DELETE FROM messages WHERE id = ?", m.id);
        this.addEvent("dropped", `id=${m.id} to=${m.to_num} no ack after ${MAX_TRIES} tries`);
      }
    }
  }

  private async poll(op: Record<string, unknown>): Promise<Response> {
    const denied = await this.auth(op.key);
    if (denied) return denied;
    this.sweep();
    const limit = Math.min(Math.max(Number(op.limit) || 10, 1), 10);
    const claimed = this.sql.exec<QueueRow>(
      "SELECT * FROM messages WHERE claim_until = 0 ORDER BY created_at LIMIT ?", limit
    ).toArray();
    const now = Date.now();
    for (const m of claimed) {
      this.sql.exec("UPDATE messages SET claim_until = ? WHERE id = ?", now + CLAIM_TIMEOUT_MS, m.id);
    }
    return j({
      messages: claimed.map((m) => ({ id: m.id, to: m.to_num, message: m.message, tries: m.tries })),
    });
  }

  private async ack(op: Record<string, unknown>): Promise<Response> {
    const denied = await this.auth(op.key);
    if (denied) return denied;
    const id = typeof op.id === "string" ? op.id : "";
    const status = op.status === "sent" ? "sent" : op.status === "failed" ? "failed" : "";
    if (!id || !status) return j({ error: `"id" and "status" ("sent"|"failed") required` }, 400);
    const error = typeof op.error === "string" ? op.error.slice(0, 300) : undefined;
    this.sql.exec("DELETE FROM messages WHERE id = ?", id);
    this.addEvent(status, `id=${id}${error ? ` error=${error}` : ""}`);
    return j({ ok: true });
  }

  private async heartbeat(op: Record<string, unknown>): Promise<Response> {
    const denied = await this.auth(op.key);
    if (denied) return denied;
    const last = Number(this.meta("last_heartbeat") ?? 0);
    const now = Date.now();
    if (now - last > HEARTBEAT_WRITE_INTERVAL_MS) this.setMeta("last_heartbeat", String(now));
    return j({ ok: true });
  }

  private async status(op: Record<string, unknown>): Promise<Response> {
    const denied = await this.auth(op.key);
    if (denied) return denied;
    this.sweep();
    const count = (where: string) =>
      this.sql.exec<{ n: number }>(`SELECT COUNT(*) AS n FROM messages WHERE ${where}`).toArray()[0]?.n ?? 0;
    const events = this.sql.exec<EventRow>("SELECT * FROM events ORDER BY ts DESC LIMIT 10").toArray();
    const lastBeat = Number(this.meta("last_heartbeat") ?? 0);
    return j({
      now: Date.now(),
      device: this.meta("name") ?? "",
      pending: count("claim_until = 0"),
      inflight: count("claim_until != 0"),
      heartbeat: lastBeat ? { ts: lastBeat } : null,
      events,
    });
  }

  private async rotate(op: Record<string, unknown>): Promise<Response> {
    const denied = await this.auth(op.key);
    if (denied) return denied;
    const newHash = String(op.newKeyHash ?? "");
    if (!/^[a-f0-9]{64}$/.test(newHash)) return j({ error: "newKeyHash must be sha-256 hex" }, 400);
    this.setMeta("key_hash", newHash);
    this.addEvent("rotated", "api key rotated");
    return j({ ok: true });
  }

  private async destroy(op: Record<string, unknown>): Promise<Response> {
    const denied = await this.auth(op.key);
    if (denied) return denied;
    // Clear rows, keep schema (deleteAll would drop the tables for the rest
    // of this isolate's lifetime and break subsequent requests).
    this.sql.exec("DELETE FROM messages");
    this.sql.exec("DELETE FROM events");
    this.sql.exec("DELETE FROM meta");
    return j({ ok: true });
  }
}
