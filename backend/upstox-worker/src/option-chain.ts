export interface OptionChainEnv {
  UPSTOX_ANALYTICS_TOKEN: string;
  QUOTE_LIMITER: { limit(options: { key: string }): Promise<{ success: boolean }> };
}

const UPSTOX_BASE = "https://api.upstox.com";
const NIFTY_INDEX_KEY = "NSE_INDEX|Nifty 50";
const REQUEST_TIMEOUT_MS = 5000;
const EXPIRY_CACHE_TTL_MS = 10 * 60_000;
const CHAIN_CACHE_TTL_MS = 15_000;
let expiryCache: { body: Record<string, unknown>; cachedAt: number } | null = null;
const chainCache = new Map<string, { body: Record<string, unknown>; cachedAt: number }>();

function json(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: {
      "content-type": "application/json; charset=utf-8",
      "cache-control": "no-store, max-age=0",
      pragma: "no-cache",
      "x-content-type-options": "nosniff",
      "referrer-policy": "no-referrer",
    },
  });
}

async function allowRequest(request: Request, env: OptionChainEnv): Promise<boolean> {
  if (!env.UPSTOX_ANALYTICS_TOKEN || !env.QUOTE_LIMITER || typeof env.QUOTE_LIMITER.limit !== "function") return false;
  const clientIp = request.headers.get("CF-Connecting-IP") ?? "unknown";
  const result = await env.QUOTE_LIMITER.limit({ key: `nifty-option-chain:${clientIp}` });
  return result.success;
}

async function upstreamJson(path: string, token: string): Promise<unknown> {
  const controller = new AbortController();
  const timeout = setTimeout(() => controller.abort(), REQUEST_TIMEOUT_MS);
  try {
    const response = await fetch(`${UPSTOX_BASE}${path}`, {
      method: "GET",
      headers: { Accept: "application/json", Authorization: `Bearer ${token}` },
      signal: controller.signal,
    });
    if (!response.ok) {
      const error = new Error(`upstream_http_${response.status}`);
      (error as Error & { status?: number }).status = response.status;
      throw error;
    }
    return await response.json();
  } finally {
    clearTimeout(timeout);
  }
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return !!value && typeof value === "object" && !Array.isArray(value);
}
function finite(value: unknown): number | null {
  return typeof value === "number" && Number.isFinite(value) ? value : null;
}

export async function handleOptionChainRequest(request: Request, url: URL, env: OptionChainEnv): Promise<Response> {
  if (request.method !== "GET") return json({ error: "method_not_allowed" }, 405);
  try {
    const allowed = await allowRequest(request, env);
    if (!env.UPSTOX_ANALYTICS_TOKEN) return json({ error: "upstox_token_not_configured" }, 503);
    if (!env.QUOTE_LIMITER || typeof env.QUOTE_LIMITER.limit !== "function") return json({ error: "rate_limiter_not_configured" }, 503);
    if (!allowed) return json({ error: "rate_limit_exceeded" }, 429);
  } catch {
    return json({ error: "rate_limiter_unavailable" }, 503);
  }

  if (url.pathname === "/v1/nifty/option-expiries") {
    if (expiryCache && Date.now() - expiryCache.cachedAt < EXPIRY_CACHE_TTL_MS) {
      return json({ ...expiryCache.body, cache: "hit" });
    }
    try {
      const payload = await upstreamJson(
        `/v2/option/contract?instrument_key=${encodeURIComponent(NIFTY_INDEX_KEY)}`,
        env.UPSTOX_ANALYTICS_TOKEN,
      );
      if (!isRecord(payload) || payload.status !== "success" || !Array.isArray(payload.data)) {
        return json({ error: "invalid_upstream_option_contracts" }, 502);
      }
      const expiries = [...new Set(payload.data
        .filter(isRecord)
        .map((item) => item.expiry)
        .filter((value): value is string => typeof value === "string" && /^\d{4}-\d{2}-\d{2}$/.test(value)))]
        .sort();
      if (expiries.length === 0) return json({ error: "no_option_expiries_available" }, 502);
      const body = { provider: "upstox", underlyingKey: NIFTY_INDEX_KEY, receivedAt: new Date().toISOString(), expiries };
      expiryCache = { body, cachedAt: Date.now() };
      return json({ ...body, cache: "refreshed" });
    } catch (error) {
      const status = (error as Error & { status?: number }).status;
      return json({ error: status ? `upstream_option_expiry_http_${status}` : "upstream_option_expiry_request_failed" }, 502);
    }
  }

  if (url.pathname !== "/v1/nifty/option-chain") return json({ error: "not_found" }, 404);
  const expiry = url.searchParams.get("expiry_date") ?? "";
  if (!/^\d{4}-\d{2}-\d{2}$/.test(expiry)) {
    return json({ error: "invalid_expiry_date", expected: "YYYY-MM-DD" }, 400);
  }
  const cacheKey = expiry;
  const cached = chainCache.get(cacheKey);
  if (cached && Date.now() - cached.cachedAt < CHAIN_CACHE_TTL_MS) {
    return json({ ...cached.body, cache: "hit" });
  }
  try {
    const payload = await upstreamJson(
      `/v2/option/chain?instrument_key=${encodeURIComponent(NIFTY_INDEX_KEY)}&expiry_date=${encodeURIComponent(expiry)}`,
      env.UPSTOX_ANALYTICS_TOKEN,
    );
    if (!isRecord(payload) || payload.status !== "success" || !Array.isArray(payload.data)) {
      return json({ error: "invalid_upstream_option_chain" }, 502);
    }
    const rawRows = payload.data.filter(isRecord);
    if (rawRows.length === 0) return json({ error: "empty_option_chain" }, 502);
    const rows = rawRows.map((row) => {
      const call = isRecord(row.call_options) ? row.call_options : {};
      const put = isRecord(row.put_options) ? row.put_options : {};
      const callMarket = isRecord(call.market_data) ? call.market_data : {};
      const putMarket = isRecord(put.market_data) ? put.market_data : {};
      const callGreeks = isRecord(call.option_greeks) ? call.option_greeks : {};
      const putGreeks = isRecord(put.option_greeks) ? put.option_greeks : {};
      const callOi = finite(callMarket.oi);
      const callPrevOi = finite(callMarket.prev_oi);
      const putOi = finite(putMarket.oi);
      const putPrevOi = finite(putMarket.prev_oi);
      return {
        strikePrice: finite(row.strike_price),
        call: {
          instrumentKey: typeof call.instrument_key === "string" ? call.instrument_key : null,
          ltp: finite(callMarket.ltp),
          volume: finite(callMarket.volume),
          oi: callOi,
          prevOi: callPrevOi,
          oiChange: callOi !== null && callPrevOi !== null ? callOi - callPrevOi : null,
          bidPrice: finite(callMarket.bid_price),
          askPrice: finite(callMarket.ask_price),
          iv: finite(callGreeks.iv),
          delta: finite(callGreeks.delta),
          gamma: finite(callGreeks.gamma),
          theta: finite(callGreeks.theta),
          vega: finite(callGreeks.vega),
        },
        put: {
          instrumentKey: typeof put.instrument_key === "string" ? put.instrument_key : null,
          ltp: finite(putMarket.ltp),
          volume: finite(putMarket.volume),
          oi: putOi,
          prevOi: putPrevOi,
          oiChange: putOi !== null && putPrevOi !== null ? putOi - putPrevOi : null,
          bidPrice: finite(putMarket.bid_price),
          askPrice: finite(putMarket.ask_price),
          iv: finite(putGreeks.iv),
          delta: finite(putGreeks.delta),
          gamma: finite(putGreeks.gamma),
          theta: finite(putGreeks.theta),
          vega: finite(putGreeks.vega),
        },
      };
    }).filter((row) => row.strikePrice !== null)
      .sort((a, b) => (a.strikePrice ?? 0) - (b.strikePrice ?? 0));
    if (rows.length === 0) return json({ error: "no_valid_option_strikes" }, 502);
    const callOiTotal = rows.reduce((sum, row) => sum + (row.call.oi ?? 0), 0);
    const putOiTotal = rows.reduce((sum, row) => sum + (row.put.oi ?? 0), 0);
    const body = {
      provider: "upstox",
      underlyingKey: NIFTY_INDEX_KEY,
      expiry,
      receivedAt: new Date().toISOString(),
      underlyingSpotPrice: finite(rawRows[0].underlying_spot_price),
      pcr: callOiTotal > 0 ? putOiTotal / callOiTotal : null,
      totalCallOi: callOiTotal,
      totalPutOi: putOiTotal,
      rows,
    };
    chainCache.set(cacheKey, { body, cachedAt: Date.now() });
    return json({ ...body, cache: "refreshed" });
  } catch (error) {
    const status = (error as Error & { status?: number }).status;
    return json({ error: status ? `upstream_option_chain_http_${status}` : "upstream_option_chain_request_failed" }, 502);
  }
}
