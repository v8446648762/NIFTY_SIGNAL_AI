// Isolated Upstox Market Data Feed V3 connectivity diagnostic.
// Does not touch the production Cloudflare Worker or Android live-feed code.
const token = process.env.UPSTOX_ANALYTICS_TOKEN;
if (!token) {
  console.error("Missing UPSTOX_ANALYTICS_TOKEN GitHub Actions secret.");
  process.exit(2);
}

const instruments = [
  "NSE_INDEX|Nifty 50",
  "NSE_INDEX|Nifty Bank",
  "NSE_INDEX|Nifty Fin Service",
];

const authResponse = await fetch("https://api.upstox.com/v3/feed/market-data-feed/authorize", {
  method: "GET",
  headers: { Accept: "application/json", Authorization: `Bearer ${token}` },
  signal: AbortSignal.timeout(10000),
});
if (!authResponse.ok) {
  console.error(`Upstox authorize failed: HTTP ${authResponse.status}`);
  process.exit(1);
}
const auth = await authResponse.json();
const wsUrl = auth?.data?.authorized_redirect_uri;
if (auth?.status !== "success" || typeof wsUrl !== "string" || !wsUrl.startsWith("wss://")) {
  console.error("Upstox authorization response was invalid (URL withheld).");
  process.exit(1);
}
console.log("AUTHORIZATION_OK");

const ws = new WebSocket(wsUrl);
let messageCount = 0;
let finished = false;
const finish = (code, message) => {
  if (finished) return;
  finished = true;
  console.log(message);
  try { ws.close(); } catch {}
  process.exitCode = code;
};

const timer = setTimeout(() => {
  finish(messageCount > 0 ? 0 : 3,
    messageCount > 0
      ? `WEBSOCKET_OK messages_received=${messageCount} (payloads intentionally not logged)`
      : "WEBSOCKET_CONNECTED_BUT_NO_TICKS (market may be closed or instruments inactive)");
}, 20000);

ws.addEventListener("open", () => {
  console.log("WEBSOCKET_CONNECTED");
  ws.send(JSON.stringify({
    guid: "mgrow-isolated-diagnostic",
    method: "sub",
    data: { mode: "ltpc", instrumentKeys: instruments },
  }));
  console.log("SUBSCRIPTION_SENT for NIFTY, BANKNIFTY, FINNIFTY");
});
ws.addEventListener("message", () => {
  messageCount += 1;
});
ws.addEventListener("error", () => {
  clearTimeout(timer);
  finish(1, "WEBSOCKET_ERROR (details withheld)");
});
ws.addEventListener("close", (event) => {
  if (!finished) {
    clearTimeout(timer);
    finish(1, `WEBSOCKET_CLOSED code=${event.code}`);
  }
});
