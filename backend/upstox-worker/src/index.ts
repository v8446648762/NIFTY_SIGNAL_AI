export interface Env {
  UPSTOX_ANALYTICS_TOKEN: string;
  QUOTE_LIMITER: { limit(options: { key: string }): Promise<{ success: boolean }> };
}

const UPSTOX_BASE = "https://api.upstox.com/v3";
const UPSTOX_V2_BASE = "https://api.upstox.com/v2";
const NIFTY_50_KEY = "NSE_INDEX|Nifty 50";
const UPSTREAM_TIMEOUT_MS = 5_000;
const MAX_QUOTE_AGE_MS = 60_000;
const CANDLE_CACHE_TTL_MS = 55_000;
const CANDLE_CACHE_MAX_STALE_MS = 10 * 60_000;
let fiveMinuteRetryAfter = 0;
let candleCache: { body: Record<string, unknown>; cachedAt: number } | null = null;
let candleFetchInFlight: Promise<Record<string, unknown>> | null = null;
let oneMinuteRetryAfter = 0;
const ONE_MINUTE_RETRY_COOLDOWN_MS = 5 * 60_000;

class UpstreamHttpError extends Error {
  constructor(readonly status: number, readonly interval: "1" | "5", readonly retryAfterSeconds: number, readonly detail: string | null) {
    super("Upstream HTTP request failed");
    this.name = "UpstreamHttpError";
  }
}
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

  if (request.method !== "GET") return json({ error: "method_not_allowed" }, 405);
  if (url.pathname !== "/v1/nifty/quote" && url.pathname !== "/v1/nifty/candles" && url.pathname !== "/v1/nifty/option-chain") return json({ error: "not_found" }, 404);
  if (!env.UPSTOX_ANALYTICS_TOKEN) {
    return json({ error: "upstox_token_not_configured" }, 503);
  }
  if (!env.QUOTE_LIMITER || typeof env.QUOTE_LIMITER.limit !== "function") return json({ error: "rate_limiter_not_configured" }, 503);
  try {
    const clientIp = request.headers.get("CF-Connecting-IP") ?? "unknown";
    const rateLimit = await env.QUOTE_LIMITER.limit({ key: `nifty-readonly:${clientIp}` });
    if (!rateLimit.success) return json({ error: "rate_limit_exceeded" }, 429, { "retry-after": "60" });
  } catch { return json({ error: "rate_limiter_unavailable" }, 503); }

  if (url.pathname === "/v1/nifty/option-chain") {\n    const expiryDate = url.searchParams.get("expiry_date") ?? "";\n    if (!/^\\d{4}-\\d{2}-\\d{2}$/.test(expiryDate)) return json({ error: "invalid_expiry_date", expected: "YYYY-MM-DD" }, 400);\n    const controller = new AbortController();\n    const timeout = setTimeout(() => controller.abort(), UPSTREAM_TIMEOUT_MS);\n    try {\n      const upstreamUrl = `${UPSTOX_V2_BASE}/option/chain?instrument_key=${encodeURIComponent(NIFTY_50_KEY)}&expiry_date=${encodeURIComponent(expiryDate)}`;\n      const upstream = await fetch(upstreamUrl, { method: "GET", headers: { Accept: "application/json", Authorization: `Bearer ${env.UPSTOX_ANALYTICS_TOKEN}` }, signal: controller.signal });\n      if (!upstream.ok) return json({ error: upstream.status === 429 ? "upstream_rate_limited" : "upstream_option_chain_error", upstreamStatus: upstream.status }, upstream.status === 429 ? 429 : 502);\n      const payload: unknown = await upstream.json();\n      const chain = extractOptionChain(payload);\n      if (!chain) return json({ error: "invalid_upstream_option_chain" }, 502);\n      return json({ provider: "upstox", instrumentKey: NIFTY_50_KEY, expiryDate, receivedAt: new Date().toISOString(), data: chain });\n    } catch (error) {\n      return json({ error: error instanceof Error && error.name === "AbortError" ? "upstream_timeout" : "upstream_option_chain_failed" }, error instanceof Error && error.name === "AbortError" ? 504 : 502);\n    } finally { clearTimeout(timeout); }\n  }\n\n  if (url.pathname === "/v1/nifty/candles") {
    const now = Date.now();
    if (candleCache && now - candleCache.cachedAt <= CANDLE_CACHE_TTL_MS) {
      return json({ ...candleCache.body, cache: "hit" });
    }
    if (now < fiveMinuteRetryAfter) {
      const retryAfterSeconds = Math.max(1, Math.ceil((fiveMinuteRetryAfter - now) / 1000));
      if (candleCache && now - candleCache.cachedAt <= CANDLE_CACHE_MAX_STALE_MS) {
        return json({ ...candleCache.body, warnings: [...((candleCache.body.warnings as string[] | undefined) ?? []), "five_minute_temporarily_unavailable"], cache: "stale-if-error" });
      }
      return json({ error: "upstream_rate_limited", upstreamStatus: 429, interval: "5", retryAfterSeconds }, 429, { "retry-after": String(retryAfterSeconds) });
    }
    try {
      if (!candleFetchInFlight) {
        candleFetchInFlight = (async () => {
          // 5m is the primary chart feed. A 1m rate-limit must never block it.
          const fiveMinute = await fetchIntradayCandles("5", env.UPSTOX_ANALYTICS_TOKEN);
          if (!fiveMinute) throw new Error("invalid_upstream_candle_payload");
          let oneMinute: Awaited<ReturnType<typeof fetchIntradayCandles>> = null;
          let oneMinuteUnavailable = false;
          if (Date.now() >= oneMinuteRetryAfter) {
            try {
              oneMinute = await fetchIntradayCandles("1", env.UPSTOX_ANALYTICS_TOKEN);
              if (!oneMinute) oneMinuteUnavailable = true;
              oneMinuteRetryAfter = oneMinuteUnavailable ? Date.now() + ONE_MINUTE_RETRY_COOLDOWN_MS : 0;
            } catch (error) {
              oneMinuteUnavailable = true;
              oneMinuteRetryAfter = Date.now() + ONE_MINUTE_RETRY_COOLDOWN_MS;
              // A 1m failure is non-fatal; keep the verified 5m feed available.
            }
          } else {
            oneMinuteUnavailable = true;
          }
          return {
            provider: "upstox",
            instrumentKey: NIFTY_50_KEY,
            receivedAt: new Date().toISOString(),
            data: { oneMinuteCandles: oneMinute ?? [], fiveMinuteCandles: fiveMinute },
            ...(oneMinuteUnavailable ? { warnings: ["one_minute_temporarily_unavailable"] } : {}),
          };
        })();
      }
      const body = await candleFetchInFlight;
      candleCache = { body, cachedAt: Date.now() };
      return json({ ...body, cache: "refreshed" });
    } catch (error) {
      if (error instanceof Error && error.name === "AbortError") {
        return json({ error: "upstream_timeout" }, 504);
      }
      if (error instanceof UpstreamHttpError) {
        if (error.interval === "5" && error.status === 429) {
          fiveMinuteRetryAfter = Date.now() + error.retryAfterSeconds * 1000;
          if (candleCache && Date.now() - candleCache.cachedAt <= CANDLE_CACHE_MAX_STALE_MS) {
            return json({ ...candleCache.body, warnings: [...((candleCache.body.warnings as string[] | undefined) ?? []), "five_minute_temporarily_unavailable"], cache: "stale-if-error" });
          }
        }
        const status = error.status === 429 ? 429 : 502;
        return json({ error: error.status === 429 ? "upstream_rate_limited" : "upstream_candle_request_failed", upstreamStatus: error.status, interval: error.interval, retryAfterSeconds: error.retryAfterSeconds, ...(error.detail ? { upstreamDetail: error.detail } : {}) }, status, error.status === 429 ? { "retry-after": String(error.retryAfterSeconds) } : {});
      }
      if (error instanceof Error && error.message === "invalid_upstream_candle_payload") {
        return json({ error: "invalid_upstream_candle_payload" }, 502);
      }
      return json({ error: "upstream_candle_request_failed", upstreamStatus: null }, 502);
    } finally {
      candleFetchInFlight = null;
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
    if (!upstream.ok) {
      const retryHeader = Number.parseInt(upstream.headers.get("retry-after") ?? "", 10);
      const retryAfterSeconds = Number.isFinite(retryHeader) ? Math.min(300, Math.max(1, retryHeader)) : 60;
      const responseText = await upstream.text();
      let detail: string | null = null;
      try {
        const parsed = JSON.parse(responseText) as Record<string, unknown>;
        const errors = Array.isArray(parsed.errors) ? parsed.errors : [];
        const first = errors[0] as Record<string, unknown> | undefined;
        const candidate = first?.message ?? parsed.message ?? parsed.error;
        if (typeof candidate === "string") detail = candidate.slice(0, 240);
      } catch { /* Do not expose arbitrary upstream response bodies. */ }
      throw new UpstreamHttpError(upstream.status, interval, retryAfterSeconds, detail);
    }
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

\n\n/** Validate and normalize the official Upstox option-chain response. */\nexport function extractOptionChain(payload: unknown): Array<Record<string, unknown>> | null {\n  if (!payload || typeof payload !== "object") return null;\n  const root = payload as Record<string, unknown>;\n  if (root.status !== "success" || !Array.isArray(root.data)) return null;\n  const rows: Array<Record<string, unknown>> = [];\n  for (const item of root.data) {\n    if (!item || typeof item !== "object") return null;\n    const row = item as Record<string, unknown>;\n    if (typeof row.strike_price !== "number" || !Number.isFinite(row.strike_price)) return null;\n    const side = (value: unknown) => {\n      if (!value || typeof value !== "object") return null;\n      const sideRow = value as Record<string, unknown>;\n      const market = sideRow.market_data && typeof sideRow.market_data === "object" ? sideRow.market_data as Record<string, unknown> : {};\n      const greeks = sideRow.option_greeks && typeof sideRow.option_greeks === "object" ? sideRow.option_greeks as Record<string, unknown> : {};\n      return { instrumentKey: sideRow.instrument_key ?? null, ltp: market.ltp ?? null, volume: market.volume ?? null, oi: market.oi ?? null, closePrice: market.close_price ?? null, bidPrice: market.bid_price ?? null, bidQuantity: market.bid_qty ?? null, askPrice: market.ask_price ?? null, askQuantity: market.ask_qty ?? null, iv: greeks.iv ?? null, delta: greeks.delta ?? null, gamma: greeks.gamma ?? null, theta: greeks.theta ?? null, vega: greeks.vega ?? null };\n    };\n    rows.push({ strikePrice: row.strike_price, call: side(row.call_options), put: side(row.put_options), underlyingSpotPrice: row.underlying_spot_price ?? null });\n  }\n  rows.sort((a, b) => Number(a.strikePrice) - Number(b.strikePrice));\n  return rows;\n}\n