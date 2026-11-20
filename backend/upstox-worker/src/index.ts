interface Env {
  UPSTOX_ANALYTICS_TOKEN: string;
  QUOTE_LIMITER: { limit(options: { key: string }): Promise<{ success: boolean }> };
}
const BASE = "https://api.upstox.com";
const NIFTY = "NSE_INDEX|Nifty 50";
const TIMEOUT_MS = 7000;
const json = (value: unknown, status = 200) => new Response(JSON.stringify(value), {
  status,
  headers: {
    "content-type": "application/json; charset=utf-8",
    "cache-control": "no-store",
    "x-content-type-options": "nosniff",
    "referrer-policy": "no-referrer"
  }
});
async function upstream(path: string, token: string): Promise<any> {
  const controller = new AbortController();
  const timeout = setTimeout(() => controller.abort(), TIMEOUT_MS);
  try {
    const response = await fetch(BASE + path, {
      headers: { Accept: "application/json", Authorization: "Bearer " + token },
      signal: controller.signal
    });
    if (!response.ok) throw new Error("upstream_" + response.status);
    return await response.json() as any;
  } finally {
    clearTimeout(timeout);
  }
}
function numberOrNull(value: any): number | null {
  return typeof value === "number" && Number.isFinite(value) ? value : null;
}
function validExpiry(value: string): boolean {
  if (!/^\d{4}-\d{2}-\d{2}$/.test(value)) return false;
  const parsed = new Date(value);
  return !Number.isNaN(parsed.getTime()) && parsed.toISOString().slice(0, 10) === value;
}
export default {
  async fetch(request: Request, env: Env): Promise<Response> {
    const url = new URL(request.url);
    if (url.pathname === "/health" && request.method === "GET") {
      return json({ service: "mgrow-readonly", status: "ready" });
    }
    const routes = ["/v1/nifty/expiries", "/v1/nifty/option-chain", "/v1/nifty/candles"];
    if (request.method !== "GET") return json({ error: "method_not_allowed" }, 405);
    if (!routes.includes(url.pathname)) return json({ error: "not_found" }, 404);
    if (!env.UPSTOX_ANALYTICS_TOKEN) return json({ error: "upstox_token_not_configured" }, 503);
    if (!env.QUOTE_LIMITER) return json({ error: "rate_limiter_not_configured" }, 503);

    // Read-only market data is public; protect the broker token with server-side storage
    // and limit requests by Cloudflare's client IP. No Google/app login is introduced.
    const clientIp = request.headers.get("CF-Connecting-IP") || "unknown";
    try {
      const limit = await env.QUOTE_LIMITER.limit({ key: "mgrow:" + clientIp });
      if (!limit.success) return json({ error: "rate_limit_exceeded" }, 429);
    } catch {
      return json({ error: "rate_limiter_unavailable" }, 503);
    }

    try {
      if (url.pathname === "/v1/nifty/expiries") {
        const payload = await upstream("/v2/option/contract?instrument_key=" + encodeURIComponent(NIFTY), env.UPSTOX_ANALYTICS_TOKEN);
        if (payload.status !== "success" || !Array.isArray(payload.data)) {
          return json({ error: "invalid_expiry_payload" }, 502);
        }
        const expiries = [...new Set(payload.data
          .map((row: any) => row?.expiry)
          .filter((value: any): value is string => typeof value === "string" && validExpiry(value)))]
          .sort();
        if (!expiries.length) return json({ error: "no_option_expiries" }, 502);
        return json({ provider: "upstox", instrumentKey: NIFTY, data: expiries });
      }

      if (url.pathname === "/v1/nifty/option-chain") {
        const expiry = url.searchParams.get("expiry_date") || "";
        if (!validExpiry(expiry)) return json({ error: "invalid_expiry_date" }, 400);
        const payload = await upstream(
          "/v2/option/chain?instrument_key=" + encodeURIComponent(NIFTY) + "&expiry_date=" + encodeURIComponent(expiry),
          env.UPSTOX_ANALYTICS_TOKEN
        );
        if (payload.status !== "success" || !Array.isArray(payload.data)) {
          return json({ error: "invalid_upstream_payload" }, 502);
        }
        const rows = payload.data.map((row: any) => {
          const ce = row.call_options || {}, pe = row.put_options || {};
          const cm = ce.market_data || {}, pm = pe.market_data || {};
          const cg = ce.option_greeks || {}, pg = pe.option_greeks || {};
          const callOi = numberOrNull(cm.oi), callPrevOi = numberOrNull(cm.prev_oi);
          const putOi = numberOrNull(pm.oi), putPrevOi = numberOrNull(pm.prev_oi);
          return {
            strike: numberOrNull(row.strike_price),
            callOi, callOiChange: callOi !== null && callPrevOi !== null ? callOi - callPrevOi : null,
            callVolume: numberOrNull(cm.volume), callLtp: numberOrNull(cm.ltp),
            callIv: numberOrNull(cg.iv), callDelta: numberOrNull(cg.delta),
            callBid: numberOrNull(cm.bid_price), callAsk: numberOrNull(cm.ask_price),
            putOi, putOiChange: putOi !== null && putPrevOi !== null ? putOi - putPrevOi : null,
            putVolume: numberOrNull(pm.volume), putLtp: numberOrNull(pm.ltp),
            putIv: numberOrNull(pg.iv), putDelta: numberOrNull(pg.delta),
            putBid: numberOrNull(pm.bid_price), putAsk: numberOrNull(pm.ask_price)
          };
        }).filter((row: any) => row.strike !== null);
        if (!rows.length) return json({ error: "empty_option_chain" }, 502);
        return json({
          provider: "upstox", instrumentKey: NIFTY, expiryDate: expiry,
          receivedAt: new Date().toISOString(), data: rows
        });
      }

      const [fivePayload, onePayload] = await Promise.all([
        upstream("/v3/historical-candle/intraday/" + encodeURIComponent(NIFTY) + "/minutes/5", env.UPSTOX_ANALYTICS_TOKEN),
        upstream("/v3/historical-candle/intraday/" + encodeURIComponent(NIFTY) + "/minutes/1", env.UPSTOX_ANALYTICS_TOKEN).catch(() => null)
      ]);
      const parseCandles = (payload: any) => {
        const raw = payload?.data?.candles;
        if (payload?.status !== "success" || !Array.isArray(raw)) return null;
        return raw.map((item: any) => ({
          timestamp: item[0], open: numberOrNull(item[1]), high: numberOrNull(item[2]),
          low: numberOrNull(item[3]), close: numberOrNull(item[4]), volume: numberOrNull(item[5]) ?? 0
        })).filter((item: any) =>
          typeof item.timestamp === "string" &&
          [item.open, item.high, item.low, item.close].every((value: any) => value !== null && value > 0) &&
          item.high >= Math.max(item.open, item.close, item.low) &&
          item.low <= Math.min(item.open, item.close, item.high) && item.volume >= 0
        ).sort((a: any, b: any) => Date.parse(a.timestamp) - Date.parse(b.timestamp)).slice(-180);
      };
      const five = parseCandles(fivePayload);
      const one = onePayload ? parseCandles(onePayload) : null;
      if (!five?.length) return json({ error: "invalid_five_minute_candles" }, 502);
      return json({
        provider: "upstox", instrumentKey: NIFTY, receivedAt: new Date().toISOString(),
        data: { fiveMinuteCandles: five, oneMinuteCandles: one || [] },
        warnings: one?.length ? [] : ["one_minute_temporarily_unavailable"]
      });
    } catch (error) {
      const timeout = error instanceof Error && error.name === "AbortError";
      return json({ error: timeout ? "upstream_timeout" : "upstream_request_failed" }, 502);
    }
  }
};
