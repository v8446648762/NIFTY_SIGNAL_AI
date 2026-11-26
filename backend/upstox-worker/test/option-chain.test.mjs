import test from "node:test";
import assert from "node:assert/strict";
import { handleRequest } from "../dist/src/index.js";

const env = {
  UPSTOX_ANALYTICS_TOKEN: "server-only-option-chain-token",
  QUOTE_LIMITER: { limit: async () => ({ success: true }) },
};
const originalFetch = globalThis.fetch;
test.afterEach(() => { globalThis.fetch = originalFetch; });

function chainPayload() {
  return {
    status: "success",
    data: [
      {
        expiry: "2026-11-26",
        pcr: 1.2,
        strike_price: 25000,
        underlying_key: "NSE_INDEX|Nifty 50",
        underlying_spot_price: 25040.5,
        call_options: {
          market_data: { ltp: 120, volume: 1000, oi: 50000, prev_oi: 45000 },
          option_greeks: { delta: 0.52, iv: 14.2 },
        },
        put_options: {
          market_data: { ltp: 95, volume: 1200, oi: 60000, prev_oi: 62000 },
          option_greeks: { delta: -0.48, iv: 15.1 },
        },
      },
      {
        expiry: "2026-11-26",
        pcr: 1.2,
        strike_price: 25100,
        underlying_key: "NSE_INDEX|Nifty 50",
        underlying_spot_price: 25040.5,
        call_options: { market_data: { ltp: 80, volume: 800, oi: 40000, prev_oi: 39000 }, option_greeks: {} },
        put_options: { market_data: { ltp: 140, volume: 900, oi: 70000, prev_oi: 68000 }, option_greeks: {} },
      },
    ],
  };
}

test("NIFTY option-chain endpoint fetches only the fixed NIFTY instrument and hides token", async () => {
  let upstream;
  globalThis.fetch = async (url, init) => {
    upstream = { url: String(url), init };
    return new Response(JSON.stringify(chainPayload()), { status: 200 });
  };
  const response = await handleRequest(new Request("https://worker.test/v1/nifty/option-chain?expiry_date=current_week", {
    headers: { "CF-Connecting-IP": "192.0.2.31" },
  }), env);
  assert.equal(response.status, 200);
  assert.match(upstream.url, /api\.upstox\.com\/v2\/option\/chain/);
  assert.match(upstream.url, /instrument_key=NSE_INDEX%7CNifty\+50/);
  assert.match(upstream.url, /expiry_date=current_week/);
  assert.equal(upstream.init.headers.Authorization, "Bearer server-only-option-chain-token");
  const body = await response.json();
  assert.equal(body.provider, "upstox");
  assert.equal(body.spotPrice, 25040.5);
  assert.equal(body.strikes.length, 2);
  assert.equal(body.strikes[0].call.oiChange, 5000);
  assert.equal(body.strikes[0].put.oiChange, -2000);
  assert.equal(body.strikes[0].put.delta, -0.48);
  assert.equal(response.headers.get("cache-control"), "no-store, max-age=0");
  assert.equal(JSON.stringify(body).includes(env.UPSTOX_ANALYTICS_TOKEN), false);
});

test("option-chain endpoint rejects invalid expiry without calling Upstox", async () => {
  globalThis.fetch = async () => { throw new Error("must not call upstream"); };
  const response = await handleRequest(new Request("https://worker.test/v1/nifty/option-chain?expiry_date=2026-02-30"), env);
  assert.equal(response.status, 400);
  assert.deepEqual(await response.json(), { error: "invalid_expiry_date" });
});

test("option-chain endpoint rejects malformed upstream data", async () => {
  globalThis.fetch = async () => new Response(JSON.stringify({ status: "success", data: [] }), { status: 200 });
  const response = await handleRequest(new Request("https://worker.test/v1/nifty/option-chain"), env);
  assert.equal(response.status, 502);
  assert.deepEqual(await response.json(), { error: "invalid_upstream_option_chain_payload" });
});

test("option-chain endpoint is rate-limited", async () => {
  env.QUOTE_LIMITER.limit = async () => ({ success: false });
  globalThis.fetch = async () => { throw new Error("must not call upstream"); };
  const response = await handleRequest(new Request("https://worker.test/v1/nifty/option-chain"), env);
  assert.equal(response.status, 429);
  env.QUOTE_LIMITER.limit = async () => ({ success: true });
});
