export interface Env {
  UPSTOX_ANALYTICS_TOKEN: string;
  MGROW_APP_PIN: string;
  MGROW_SESSION_SECRET: string;
  QUOTE_LIMITER: { limit(options: { key: string }): Promise<{ success: boolean }> };
  LOGIN_LIMITER: { limit(options: { key: string }): Promise<{ success: boolean }> };
}

const UPSTOX_BASE = "https://api.upstox.com/v3";
const NIFTY_50_KEY = "NSE_INDEX|Nifty 50";
const UPSTREAM_TIMEOUT_MS = 5_000;
const MAX_QUOTE_AGE_MS = 60_000;
const MAX_FUTURE_SKEW_MS = 5_000;

function json(body: unknown, status = 200, extraHeaders: Record<string, string> = {}): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: {
      "content-type": "application/json; charset=utf-8",
      "cache-control": "no-store, max-age=0",
      pragma: "no-cache",
      "x-content-type-options": "nosniff",
      "referrer-policy": "no-referrer",
      ...extraHeaders,
    },
  });
}

export default { fetch: handleRequest };

export async function handleRequest(request: Request, env: Env): Promise<Response> {
  const url = new URL(request.url);

  if (url.pathname === "/health" && request.method === "GET") {
    return json({ service: "mgrow-upstox-proxy", status: "ready", brokerConnected: false });
  }

  if (url.pathname === "/v1/auth/login") {
    if (request.method !== "POST") return json({ error: "method_not_allowed" }, 405);
    if (!env.MGROW_APP_PIN || !env.MGROW_SESSION_SECRET || env.MGROW_APP_PIN.length < 8 || env.MGROW_SESSION_SECRET.length < 32) {
      return json({ error: "auth_secrets_not_configured" }, 503);
    }
    if (!env.LOGIN_LIMITER || typeof env.LOGIN_LIMITER.limit !== "function") return json({ error: "login_limiter_not_configured" }, 503);
    try {
      const ip = request.headers.get("CF-Connecting-IP") ?? "unknown";
      const limited = await env.LOGIN_LIMITER.limit({ key: `pin-login:${ip}` });
      if (!limited.success) return json({ error: "login_rate_limit_exceeded" }, 429, { "retry-after": "60" });
    } catch { return json({ error: "login_limiter_unavailable" }, 503); }
    let body: unknown;
    try { body = await request.json(); } catch { return json({ error: "invalid_json" }, 400); }
    const pin = body && typeof body === "object" ? (body as Record<string, unknown>).pin : null;
    if (typeof pin !== "string" || pin.length < 8 || pin.length > 32 || !constantTimeEqual(pin, env.MGROW_APP_PIN)) {
      return json({ error: "invalid_credentials" }, 401);
    }
    return json({ token: await createSessionToken(env.MGROW_SESSION_SECRET), expiresIn: 43200 });
  }

  if (request.method !== "GET") return json({ error: "method_not_allowed" }, 405);
  if (url.pathname !== "/v1/nifty/quote" && url.pathname !== "/v1/nifty/candles") return json({ error: "not_found" }, 404);
  if (!env.UPSTOX_ANALYTICS_TOKEN || !env.MGROW_SESSION_SECRET || env.MGROW_SESSION_SECRET.length < 32) {
    return json({ error: "server_secrets_not_configured" }, 503);
  }
  const authorization = request.headers.get("authorization") ?? "";
  const match = authorization.match(/^Bearer ([^\\s]+)$/i);
  if (!match || !(await verifySessionToken(match[1], env.MGROW_SESSION_SECRET))) return json({ error: "unauthorized" }, 401);
  if (!env.QUOTE_LIMITER || typeof env.QUOTE_LIMITER.limit !== "function") return json({ error: "rate_limiter_not_configured" }, 503);
  try {
    const rateLimit = await env.QUOTE_LIMITER.limit({ key: "mgrow-owner" });
    if (!rateLimit.success) return json({ error: "rate_limit_exceeded" }, 429, { "retry-after": "60" });
  } catch { return json({ error: "rate_limiter_unavailable" }, 503); }

  if (url.pathname === "/v1/nifty/candles") {
    try {
      const [oneMinute, fiveMinute] = await Promise.all([
        fetchIntradayCandles("1", env.UPSTOX_ANALYTICS_TOKEN),
        fetchIntradayCandles("5", env.UPSTOX_ANALYTICS_TOKEN),
      ]);
      if (!oneMinute || !fiveMinute) {
        return json({ error: "invalid_upstream_candle_payload" }, 502);
      }
      return json({
        provider: "upstox",
        instrumentKey: NIFTY_50_KEY,
        receivedAt: new Date().toISOString(),
        data: { oneMinuteCandles: oneMinute, fiveMinuteCandles: fiveMinute },
      });
    } catch (error) {
      if (error instanceof Error && error.name === "AbortError") {
        return json({ error: "upstream_timeout" }, 504);
      }
      return json({ error: "upstream_candle_request_failed" }, 502);
    }
  }

  const controller = new AbortController();
  const timeout = setTimeout(() => controller.abort(), UPSTREAM_TIMEOUT_MS);
  try {
    const upstream = await fetch(
      `${UPSTOX_BASE}/market-quote/quotes?instrument_key=${encodeURIComponent(NIFTY_50_KEY)}`,
      {
        method: "GET",
        headers: {
          Accept: "application/json",
          Authorization: `Bearer ${env.UPSTOX_ANALYTICS_TOKEN}`,
        },
        signal: controller.signal,
      },
    );
    if (!upstream.ok) {
      return json({ error: "upstream_market_data_error", upstreamStatus: upstream.status }, 502);
    }
    const payload: unknown = await upstream.json();
    const quote = extractValidQuote(payload);
    if (!quote) return json({ error: "invalid_upstream_payload" }, 502);
    return json({
      provider: "upstox",
      instrumentKey: NIFTY_50_KEY,
      receivedAt: new Date().toISOString(),
      quoteTimestamp: quote.timestamp,
      data: quote,
    });
  } catch (error) {
    if (error instanceof Error && error.name === "AbortError") {
      return json({ error: "upstream_timeout" }, 504);
    }
    return json({ error: "upstream_request_failed" }, 502);
  } finally {
    clearTimeout(timeout);
  }
}

/** Fetches a fixed NIFTY index interval; caller cannot choose arbitrary instruments. */
async function fetchIntradayCandles(
  interval: "1" | "5",
  token: string,
): Promise<Array<{ timestamp: string; open: number; high: number; low: number; close: number; volume: number; openInterest: number }> | null> {
  const controller = new AbortController();
  const timeout = setTimeout(() => controller.abort(), UPSTREAM_TIMEOUT_MS);
  try {
    const instrument = encodeURIComponent(NIFTY_50_KEY);
    const upstream = await fetch(
      `${UPSTOX_BASE}/historical-candle/intraday/${instrument}/minutes/${interval}`,
      {
        method: "GET",
        headers: { Accept: "application/json", Authorization: `Bearer ${token}` },
        signal: controller.signal,
      },
    );
    if (!upstream.ok) throw new Error(`Upstox candle API returned ${upstream.status}`);
    const payload: unknown = await upstream.json();
    return extractValidCandles(payload);
  } finally {
    clearTimeout(timeout);
  }
}

/** Upstox V3 candle rows are [timestamp, open, high, low, close, volume, OI]. */
export function extractValidCandles(payload: unknown): Array<{
  timestamp: string; open: number; high: number; low: number; close: number; volume: number; openInterest: number;
}> | null {
  if (!payload || typeof payload !== "object") return null;
  const root = payload as Record<string, unknown>;
  if (root.status !== "success" || !root.data || typeof root.data !== "object") return null;
  const raw = (root.data as Record<string, unknown>).candles;
  if (!Array.isArray(raw)) return null;

  const candles = [];
  for (const row of raw) {
    if (!Array.isArray(row) || row.length < 6 || typeof row[0] !== "string") return null;
    const [timestamp, open, high, low, close, volume, openInterest = 0] = row;
    const time = Date.parse(timestamp);
    if (!Number.isFinite(time) || time > Date.now() + MAX_FUTURE_SKEW_MS) return null;
    if ([open, high, low, close].some((value) => typeof value !== "number" || !Number.isFinite(value) || value <= 0)) return null;
    if (typeof open !== "number" || typeof high !== "number" || typeof low !== "number" || typeof close !== "number") return null;
    if (high < Math.max(open, close, low) || low > Math.min(open, close, high)) return null;
    if (typeof volume !== "number" || !Number.isFinite(volume) || volume < 0) return null;
    if (typeof openInterest !== "number" || !Number.isFinite(openInterest) || openInterest < 0) return null;
    candles.push({ timestamp, open, high, low, close, volume, openInterest });
  }
  candles.sort((a, b) => Date.parse(a.timestamp) - Date.parse(b.timestamp));
  return candles.slice(-180);
}

function extractValidQuote(payload: unknown): Record<string, unknown> | null {
  if (!payload || typeof payload !== "object") return null;
  const root = payload as Record<string, unknown>;
  if (root.status !== "success" || !root.data || typeof root.data !== "object") return null;
  const data = root.data as Record<string, unknown>;
  const responseKey = NIFTY_50_KEY.replace("|", ":");
  const quote = data[responseKey];
  if (!quote || typeof quote !== "object" || Array.isArray(quote)) return null;
  const record = quote as Record<string, unknown>;
  if (typeof record.last_price !== "number" || !Number.isFinite(record.last_price) || record.last_price <= 0) return null;
  if (typeof record.timestamp !== "string") return null;
  const quoteTime = Date.parse(record.timestamp);
  if (!Number.isFinite(quoteTime)) return null;
  const age = Date.now() - quoteTime;
  if (age > MAX_QUOTE_AGE_MS || age < -MAX_FUTURE_SKEW_MS) return null;
  return record;
}


function base64Url(bytes: Uint8Array): string {
  let binary = "";
  for (const byte of bytes) binary += String.fromCharCode(byte);
  return btoa(binary).replace(/\\+/g, "-").replace(/\\//g, "_").replace(/=+$/g, "");
}

function constantTimeEqual(a: string, b: string): boolean {
  const aa = new TextEncoder().encode(a), bb = new TextEncoder().encode(b);
  let diff = aa.length ^ bb.length;
  for (let i = 0; i < Math.max(aa.length, bb.length); i++) diff |= (aa[i] ?? 0) ^ (bb[i] ?? 0);
  return diff === 0;
}

async function signSession(payload: string, secret: string): Promise<string> {
  const key = await crypto.subtle.importKey("raw", new TextEncoder().encode(secret), { name: "HMAC", hash: "SHA-256" }, false, ["sign"]);
  return base64Url(new Uint8Array(await crypto.subtle.sign("HMAC", key, new TextEncoder().encode(payload))));
}

async function createSessionToken(secret: string): Promise<string> {
  const payload = base64Url(new TextEncoder().encode(JSON.stringify({ sub: "mgrow-owner", exp: Math.floor(Date.now() / 1000) + 43200 })));
  return payload + "." + await signSession(payload, secret);
}

async function verifySessionToken(token: string, secret: string): Promise<boolean> {
  try {
    const parts = token.split(".");
    if (parts.length !== 2 || !constantTimeEqual(parts[1], await signSession(parts[0], secret))) return false;
    const normalized = parts[0].replace(/-/g, "+").replace(/_/g, "/");
    const decoded = atob(normalized.padEnd(Math.ceil(normalized.length / 4) * 4, "="));
    const claims = JSON.parse(decoded) as { sub?: string; exp?: number };
    return claims.sub === "mgrow-owner" && typeof claims.exp === "number" && claims.exp > Math.floor(Date.now() / 1000);
  } catch { return false; }
}
