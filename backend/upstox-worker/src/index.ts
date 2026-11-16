import { verifyGoogleIdToken } from "./google-auth.js";

export interface Env {
  UPSTOX_ANALYTICS_TOKEN: string;
  GOOGLE_CLIENT_ID: string;
  MGROW_ALLOWED_GOOGLE_EMAIL: string;
  QUOTE_LIMITER: { limit(options: { key: string }): Promise<{ success: boolean }> };
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

  if (request.method !== "GET") return json({ error: "method_not_allowed" }, 405);
  if (url.pathname === "/health") {
    return json({ service: "mgrow-upstox-proxy", status: "ready", brokerConnected: false });
  }
  if (url.pathname !== "/v1/nifty/quote" && url.pathname !== "/v1/nifty/candles") {
    return json({ error: "not_found" }, 404);
  }
  if (!env.UPSTOX_ANALYTICS_TOKEN || !env.GOOGLE_CLIENT_ID || !env.MGROW_ALLOWED_GOOGLE_EMAIL) {
    return json({ error: "server_secrets_not_configured" }, 503);
  }

  const authorization = request.headers.get("authorization") ?? "";
  const match = authorization.match(/^Bearer ([^\\s]+)$/i);
  if (!match) return json({ error: "unauthorized" }, 401);
  const identity = await verifyGoogleIdToken(match[1], env);
  if (!identity) return json({ error: "unauthorized" }, 401);

  if (!env.QUOTE_LIMITER || typeof env.QUOTE_LIMITER.limit !== "function") {
    return json({ error: "rate_limiter_not_configured" }, 503);
  }
  try {
    const rateLimit = await env.QUOTE_LIMITER.limit({ key: identity.email.toLowerCase() });
    if (!rateLimit.success) {
      return json({ error: "rate_limit_exceeded" }, 429, { "retry-after": "60" });
    }
  } catch {
    return json({ error: "rate_limiter_unavailable" }, 503);
  }

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
    const prices = [open, high, low, close];
    if (!Number.isFinite(time) || time > Date.now() + MAX_FUTURE_SKEW_MS) return null;
    if (!prices.every((value) => typeof value === "number" && Number.isFinite(value) && value > 0)) return null;
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
