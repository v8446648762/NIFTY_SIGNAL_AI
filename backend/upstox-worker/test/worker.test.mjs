import test from "node:test";
import assert from "node:assert/strict";
import { handleRequest, extractValidCandles } from "../dist/src/index.js";

const emptyEnv = {
  UPSTOX_ANALYTICS_TOKEN: "",
  QUOTE_LIMITER: { limit: async () => ({ success: true }) },
};
const configuredEnv = {
  ...emptyEnv,
  UPSTOX_ANALYTICS_TOKEN: "server-only-test-token",
};
const originalFetch = globalThis.fetch;
test.afterEach(() => { globalThis.fetch = originalFetch; });

test("GET /health reports worker status without claiming broker connection", async () => {
  const response = await handleRequest(new Request("https://worker.example/health"), emptyEnv);
  assert.equal(response.status, 200);
  assert.deepEqual(await response.json(), { service: "mgrow-upstox-proxy", status: "ready", brokerConnected: false });
  assert.equal(response.headers.get("cache-control"), "no-store, max-age=0");
});

test("non-GET requests to market endpoints are rejected", async () => {
  const response = await handleRequest(new Request("https://worker.example/v1/nifty/quote", { method: "POST" }), emptyEnv);
  assert.equal(response.status, 405);
});

test("unknown paths are rejected", async () => {
  const response = await handleRequest(new Request("https://worker.example/unknown"), emptyEnv);
  assert.equal(response.status, 404);
});

test("market endpoint reports missing Upstox token without requiring app PIN", async () => {
  const response = await handleRequest(new Request("https://worker.example/v1/nifty/quote"), emptyEnv);
  assert.equal(response.status, 503);
  assert.deepEqual(await response.json(), { error: "upstox_token_not_configured" });
});

test("PIN login endpoint is not used for live market data", async () => {
  const response = await handleRequest(new Request("https://worker.example/v1/auth/login", {
    method: "POST", headers: { "content-type": "application/json" }, body: JSON.stringify({ pin: "not-used" }),
  }), configuredEnv);
  assert.equal(response.status, 405);
});

test("public quote endpoint works without app credentials", async () => {
  globalThis.fetch = async () => new Response(JSON.stringify({
    status: "success",
    data: { "NSE_INDEX:Nifty 50": { last_price: 24123.5, timestamp: new Date().toISOString() } },
  }), { status: 200 });
  const response = await handleRequest(new Request("https://worker.example/v1/nifty/quote", {
    headers: { "CF-Connecting-IP": "192.0.2.8" },
  }), configuredEnv);
  assert.equal(response.status, 200);
  assert.equal((await response.json()).data.last_price, 24123.5);
});

test("Upstox V3 candles are validated and sorted oldest first", () => {
  const payload = { status: "success", data: { candles: [
    ["2025-11-17T09:16:00+05:30", 25010, 25020, 25005, 25015, 1200, 0],
    ["2025-11-17T09:15:00+05:30", 25000, 25015, 24995, 25010, 900, 0],
  ] } };
  const result = extractValidCandles(payload);
  assert.equal(result?.length, 2);
  assert.equal(result?.[0].timestamp, "2025-11-17T09:15:00+05:30");
  assert.equal(result?.[1].close, 25015);
});

test("Upstox V3 candle validation rejects impossible OHLC values", () => {
  const payload = { status: "success", data: { candles: [
    ["2025-11-17T09:15:00+05:30", 25000, 24900, 25010, 25005, 900, 0],
  ] } };
  assert.equal(extractValidCandles(payload), null);
});
