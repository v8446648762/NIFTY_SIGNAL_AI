import test from "node:test";
import assert from "node:assert/strict";
import { handleRequest, extractValidCandles, extractOptionChain, extractOptionExpiries } from "../dist/src/index.js";

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


test("5m candles remain available when the optional 1m request is rate limited", async () => {
  const calls = [];
  globalThis.fetch = async (url) => {
    calls.push(String(url));
    if (String(url).endsWith("/minutes/1")) {
      return new Response(JSON.stringify({ status: "error" }), { status: 429 });
    }
    return new Response(JSON.stringify({ status: "success", data: { candles: [
      ["2026-11-17T09:15:00+05:30", 25000, 25020, 24990, 25010, 900, 0],
    ] } }), { status: 200 });
  };
  const response = await handleRequest(new Request("https://worker.example/v1/nifty/candles", {
    headers: { "CF-Connecting-IP": "192.0.2.44" },
  }), configuredEnv);
  const body = await response.json();
  assert.equal(response.status, 200);
  assert.equal(body.data.fiveMinuteCandles.length, 1);
  assert.deepEqual(body.data.oneMinuteCandles, []);
  assert.deepEqual(body.warnings, ["one_minute_temporarily_unavailable"]);
  assert.match(calls[0], /minutes\/5$/);
  assert.match(calls[1], /minutes\/1$/);
});


test("option expiry parser returns unique sorted valid expiry dates", () => {
  const result = extractOptionExpiries({ status: "success", data: [
    { expiry: "2026-12-03" }, { expiry: "2026-11-26" }, { expiry: "2026-12-03" },
    { expiry: "2026-02-30" }, { expiry: null },
  ] });
  assert.deepEqual(result, ["2026-11-26", "2026-12-03"]);
});

test("option expiry parser rejects malformed upstream payload", () => {
  assert.equal(extractOptionExpiries({ status: "error", data: [] }), null);
});

test("option chain parser normalizes CE and PE rows and sorts strikes", () => {
  const result = extractOptionChain({ status: "success", data: [
    { strike_price: 25000, call_options: { instrument_key: "CE", market_data: { ltp: 120, oi: 500 }, option_greeks: { iv: 12, delta: 0.5 } }, put_options: { instrument_key: "PE", market_data: { ltp: 90, oi: 600 }, option_greeks: { iv: 13 } } },
    { strike_price: 24950, call_options: null, put_options: null },
  ] });
  assert.equal(result?.length, 2);
  assert.equal(result?.[0].strikePrice, 24950);
  assert.equal(result?.[1].call.ltp, 120);
  assert.equal(result?.[1].put.oi, 600);
  assert.equal(result?.[1].call.delta, 0.5);
});

test("option chain endpoint rejects invalid calendar expiry without upstream request", async () => {
  let called = false;
  globalThis.fetch = async () => { called = true; return new Response("{}"); };
  const response = await handleRequest(new Request("https://worker.example/v1/nifty/option-chain?expiry_date=2026-02-30", {
    headers: { "CF-Connecting-IP": "192.0.2.88" },
  }), configuredEnv);
  assert.equal(response.status, 400);
  assert.equal(called, false);
});

test("option expiries endpoint returns validated expiry dates from Upstox", async () => {
  let requested = "";
  globalThis.fetch = async (url, options) => {
    requested = String(url);
    assert.match(options.headers.Authorization, /^Bearer server-only-test-token$/);
    return new Response(JSON.stringify({ status: "success", data: [
      { expiry: "2026-11-26" }, { expiry: "2026-12-03" },
    ] }), { status: 200 });
  };
  const response = await handleRequest(new Request("https://worker.example/v1/nifty/option-expiries", {
    headers: { "CF-Connecting-IP": "192.0.2.89" },
  }), configuredEnv);
  const body = await response.json();
  assert.equal(response.status, 200);
  assert.deepEqual(body.data, ["2026-11-26", "2026-12-03"]);
  assert.match(requested, /\/option\/contract\?/);
});
