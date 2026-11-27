// Isolated diagnostic Worker only. It does not proxy data or touch the production Worker.
const INSTRUMENTS = [
  "NSE_INDEX|Nifty 50",
  "NSE_INDEX|Nifty Bank",
  "NSE_INDEX|Nifty Fin Service",
];

function json(data, status = 200) {
  return new Response(JSON.stringify(data), {
    status,
    headers: { "content-type": "application/json; charset=utf-8", "cache-control": "no-store" },
  });
}

export default {
  async fetch(request, env) {
    const url = new URL(request.url);
    if (request.method !== "GET" || url.pathname !== "/diagnostic") {
      return json({ error: "Not found" }, 404);
    }
    if (!env.UPSTOX_ANALYTICS_TOKEN || !env.TEST_ACCESS_KEY) {
      return json({ error: "Worker secrets are not configured" }, 500);
    }
    if (request.headers.get("x-test-key") !== env.TEST_ACCESS_KEY) {
      return json({ error: "Unauthorized" }, 401);
    }

    let authResponse;
    try {
      authResponse = await fetch("https://api.upstox.com/v3/feed/market-data-feed/authorize", {
        headers: {
          Accept: "application/json",
          Authorization: `Bearer ${env.UPSTOX_ANALYTICS_TOKEN}`,
        },
        signal: AbortSignal.timeout(10000),
      });
    } catch {
      return json({ stage: "authorization", ok: false, reason: "Upstox authorization request failed" }, 502);
    }
    if (!authResponse.ok) {
      return json({ stage: "authorization", ok: false, http_status: authResponse.status }, 502);
    }
    const auth = await authResponse.json();
    const wsUrl = auth?.data?.authorized_redirect_uri;
    if (auth?.status !== "success" || typeof wsUrl !== "string" || !wsUrl.startsWith("wss://")) {
      return json({ stage: "authorization", ok: false, reason: "Invalid authorization response" }, 502);
    }

    let ws;
    try {
      ws = new WebSocket(wsUrl);
    } catch {
      return json({ stage: "websocket", ok: false, reason: "WebSocket constructor failed" }, 502);
    }

    const result = await new Promise((resolve) => {
      let messageCount = 0;
      let subscribed = false;
      let settled = false;
      const finish = (data) => {
        if (settled) return;
        settled = true;
        try { ws.close(1000, "diagnostic complete"); } catch {}
        resolve({
          authorization: "ok",
          websocket: data.websocket,
          subscription_sent: subscribed,
          messages_received: messageCount,
          instruments_requested: INSTRUMENTS,
          note: messageCount > 0
            ? "Frames received. This diagnostic counts frames only; it does not decode prices."
            : "No frames received during the sample window; market closure or inactive instruments may explain this.",
          ...data.extra,
        });
      };

      const timer = setTimeout(() => finish({ websocket: "connected", extra: {} }), 15000);
      ws.addEventListener("open", () => {
        setTimeout(() => {
          if (settled || ws.readyState !== WebSocket.OPEN) return;
          const payload = {
            guid: "mgrow-cloudflare-isolated-test",
            method: "sub",
            data: { mode: "ltpc", instrumentKeys: INSTRUMENTS },
          };
          try {
            ws.send(new TextEncoder().encode(JSON.stringify(payload)));
            subscribed = true;
          } catch {
            clearTimeout(timer);
            finish({ websocket: "connected", extra: { reason: "Subscription send failed" } });
          }
        }, 1000);
      });
      ws.addEventListener("message", () => {
        messageCount += 1;
      });
      ws.addEventListener("error", () => {
        clearTimeout(timer);
        finish({ websocket: "error", extra: { reason: "WebSocket error; payload details withheld" } });
      });
      ws.addEventListener("close", (event) => {
        if (!settled) {
          clearTimeout(timer);
          finish({ websocket: "closed", extra: { close_code: event.code } });
        }
      });
    });

    return json(result);
  },
};
