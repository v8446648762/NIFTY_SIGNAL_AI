export interface Env {
  UPSTOX_ANALYTICS_TOKEN: string;
  MGROW_CLIENT_KEY: string;
}

const UPSTOX_BASE = "https://api.upstox.com/v2";
const NIFTY_50_KEY = "NSE_INDEX|Nifty 50";

function json(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: {
      "content-type": "application/json; charset=utf-8",
      "cache-control": "no-store",
      "x-content-type-options": "nosniff",
    },
  });
}

export default {
  async fetch(request: Request, env: Env): Promise<Response> {
    const url = new URL(request.url);
    if (request.method !== "GET") return json({ error: "method_not_allowed" }, 405);
    if (url.pathname === "/health") return json({ service: "mgrow-upstox-proxy", status: "ready", brokerConnected: false });

    if (url.pathname !== "/v1/nifty/quote") return json({ error: "not_found" }, 404);
    if (!env.UPSTOX_ANALYTICS_TOKEN || !env.MGROW_CLIENT_KEY) {
      return json({ error: "server_secrets_not_configured" }, 503);
    }

    const suppliedKey = request.headers.get("x-mgrow-client-key") ?? "";
    if (suppliedKey.length !== env.MGROW_CLIENT_KEY.length ||
        !timingSafeEqual(suppliedKey, env.MGROW_CLIENT_KEY)) {
      return json({ error: "unauthorized" }, 401);
    }

    const upstream = await fetch(
      `${UPSTOX_BASE}/market-quote/ltp?instrument_key=${encodeURIComponent(NIFTY_50_KEY)}`,
      {
        method: "GET",
        headers: {
          Accept: "application/json",
          Authorization: `Bearer ${env.UPSTOX_ANALYTICS_TOKEN}`,
        },
      },
    );

    if (!upstream.ok) {
      // Do not forward upstream headers or secrets; expose only status category.
      return json({ error: "upstream_market_data_error", upstreamStatus: upstream.status }, 502);
    }

    const payload: any = await upstream.json();
    if (payload?.status !== "success" || !payload?.data || typeof payload.data !== "object") {
      return json({ error: "invalid_upstream_payload" }, 502);
    }

    return json({
      provider: "upstox",
      instrumentKey: NIFTY_50_KEY,
      receivedAt: new Date().toISOString(),
      data: payload.data,
    });
  },
};

function timingSafeEqual(a: string, b: string): boolean {
  let diff = a.length ^ b.length;
  for (let i = 0; i < Math.max(a.length, b.length); i++) {
    diff |= (a.charCodeAt(i) || 0) ^ (b.charCodeAt(i) || 0);
  }
  return diff === 0;
}
