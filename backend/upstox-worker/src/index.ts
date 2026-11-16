export interface Env {
  UPSTOX_ANALYTICS_TOKEN: string;
  // Temporary development gate only. Do not treat a key embedded in an APK as production authentication.
  MGROW_CLIENT_KEY: string;
}

const UPSTOX_BASE = "https://api.upstox.com/v2";
const NIFTY_50_KEY = "NSE_INDEX|Nifty 50";
const UPSTREAM_TIMEOUT_MS = 5_000;

function json(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: {
      "content-type": "application/json; charset=utf-8",
      "cache-control": "no-store, max-age=0",
      "pragma": "no-cache",
      "x-content-type-options": "nosniff",
      "referrer-policy": "no-referrer",
    },
  });
}

export default {
  async fetch(request: Request, env: Env): Promise<Response> {
    const url = new URL(request.url);

    if (request.method !== "GET") {
      return json({ error: "method_not_allowed" }, 405);
    }
    if (url.pathname === "/health") {
      return json({ service: "mgrow-upstox-proxy", status: "ready", brokerConnected: false });
    }
    if (url.pathname !== "/v1/nifty/quote") {
      return json({ error: "not_found" }, 404);
    }
    if (!env.UPSTOX_ANALYTICS_TOKEN || !env.MGROW_CLIENT_KEY) {
      return json({ error: "server_secrets_not_configured" }, 503);
    }

    const suppliedKey = request.headers.get("x-mgrow-client-key") ?? "";
    if (
      suppliedKey.length !== env.MGROW_CLIENT_KEY.length ||
      !timingSafeEqual(suppliedKey, env.MGROW_CLIENT_KEY)
    ) {
      return json({ error: "unauthorized" }, 401);
    }

    const controller = new AbortController();
    const timeout = setTimeout(() => controller.abort(), UPSTREAM_TIMEOUT_MS);

    try {
      const upstream = await fetch(
        `${UPSTOX_BASE}/market-quote/ltp?instrument_key=${encodeURIComponent(NIFTY_50_KEY)}`,
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
      if (!quote) {
        return json({ error: "invalid_upstream_payload" }, 502);
      }

      return json({
        provider: "upstox",
        instrumentKey: NIFTY_50_KEY,
        receivedAt: new Date().toISOString(),
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
  },
};

function extractValidQuote(payload: unknown): Record<string, unknown> | null {
  if (!payload || typeof payload !== "object") return null;
  const root = payload as Record<string, unknown>;
  if (root.status !== "success" || !root.data || typeof root.data !== "object") return null;

  const data = root.data as Record<string, unknown>;
  const quote = data[NIFTY_50_KEY];
  if (!quote || typeof quote !== "object" || Array.isArray(quote)) return null;

  const record = quote as Record<string, unknown>;
  if (typeof record.last_price !== "number" || !Number.isFinite(record.last_price)) return null;
  if (record.last_price <= 0) return null;
  return record;
}

function timingSafeEqual(a: string, b: string): boolean {
  let diff = a.length ^ b.length;
  for (let i = 0; i < Math.max(a.length, b.length); i++) {
    diff |= (a.charCodeAt(i) || 0) ^ (b.charCodeAt(i) || 0);
  }
  return diff === 0;
}
