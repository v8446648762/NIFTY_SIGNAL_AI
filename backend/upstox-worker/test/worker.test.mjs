import test from "node:test";
import assert from "node:assert/strict";
import { handleRequest, extractValidCandles } from "../dist/src/index.js";

const env = {
  UPSTOX_ANALYTICS_TOKEN: "server-only-test-token",
  QUOTE_LIMITER: { limit: async () => ({ success: true }) },
};
const request = (url) => new Request(url, { headers: { "CF-Connecting-IP": "203.0.113.10" } });

test("health reports worker status without claiming broker connection", async () => {
  const response = await handleRequest(new Request("https://worker.example/health"), env);
  assert.deepEqual(await response.json(), { service: "mgrow-upstox-proxy", status: "ready", brokerConnected: false });
});
test("non-GET requests are rejected", async () => {
  const response = await handleRequest(new Request("https://worker.example/v1/nifty/quote", { method: "POST" }), env);
  assert.equal(response.status, 405);
});
test("unknown paths are rejected", async () => {
  const response = await handleRequest(request("https://worker.example/unknown"), env);
  assert.equal(response.status, 404);
});
test("quote endpoint fails closed when server secret is missing", async () => {
  const response = await handleRequest(request("https://worker.example/v1/nifty/quote"), { ...env, UPSTOX_ANALYTICS_TOKEN: "" });
  assert.equal(response.status, 503);
});
test("quote endpoint requires edge client IP for rate limit", async () => {
  const response = await handleRequest(new Request("https://worker.example/v1/nifty/quote"), env);
  assert.equal(response.status, 503);
  assert.deepEqual(await response.json(), { error: "client_ip_unavailable" });
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
