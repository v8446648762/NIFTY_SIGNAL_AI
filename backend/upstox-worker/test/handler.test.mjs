import test from "node:test";
import assert from "node:assert/strict";
import { handleRequest } from "../dist/src/index.js";

const env = {
  UPSTOX_ANALYTICS_TOKEN: "server-only-test-token",
  QUOTE_LIMITER: { limit: async () => ({ success: true }) },
};
const originalFetch = globalThis.fetch;
test.afterEach(() => { globalThis.fetch = originalFetch; });

function quotePayload(quote = { last_price: 24123.5, instrument_token: "NSE_INDEX|Nifty 50" }, timestamp = new Date().toISOString()) {
  return { status: "success", data: { "NSE_INDEX:Nifty 50": { ...quote, timestamp } } };
}

test("health endpoint is public and does not call broker", async () => {
  globalThis.fetch = async () => { throw new Error("fetch should not be called"); };
  const response = await handleRequest(new Request("https://worker.test/health"), env);
  assert.equal(response.status, 200);
  assert.equal((await response.json()).brokerConnected, false);
});

test("public NIFTY quote is rate-limited by client IP", async () => {
  let limitedKey;
  env.QUOTE_LIMITER.limit = async ({ key }) => { limitedKey = key; return { success: true }; };
  globalThis.fetch = async () => new Response(JSON.stringify(quotePayload()), { status: 200 });
  const response = await handleRequest(new Request("https://worker.test/v1/nifty/quote", {
    headers: { "CF-Connecting-IP": "192.0.2.10" },
  }), env);
  assert.equal(response.status, 200);
  assert.equal(limitedKey, "nifty-readonly:192.0.2.10");
  const body = await response.json();
  assert.equal(body.data.last_price, 24123.5);
  assert.ok(Number.isFinite(Date.parse(body.quoteTimestamp)));
});

test("returns validated NIFTY quote and sends broker token only upstream", async () => {
  let upstreamRequest;
  globalThis.fetch = async (url, init) => {
    upstreamRequest = { url: String(url), init };
    return new Response(JSON.stringify(quotePayload()), { status: 200, headers: { "content-type": "application/json" } });
  };
  const response = await handleRequest(new Request("https://worker.test/v1/nifty/quote", {
    headers: { "CF-Connecting-IP": "192.0.2.11" },
  }), env);
  assert.equal(response.status, 200);
  const body = await response.json();
  assert.equal(body.data.last_price, 24123.5);
  assert.match(upstreamRequest.init.headers.Authorization, /^Bearer server-only-test-token$/);
  assert.equal(response.headers.get("cache-control"), "no-store, max-age=0");
});

test("rejects stale quote timestamps", async () => {
  globalThis.fetch = async () => new Response(JSON.stringify(quotePayload(
    { last_price: 25001.25 },
    new Date(Date.now() - 10 * 60_000).toISOString(),
  )), { status: 200 });
  const response = await handleRequest(new Request("https://worker.test/v1/nifty/quote", {
    headers: { "CF-Connecting-IP": "192.0.2.12" },
  }), env);
  assert.equal(response.status, 502);
  assert.deepEqual(await response.json(), { error: "invalid_upstream_payload" });
});

test("does not expose broker token in successful response", async () => {
  globalThis.fetch = async () => new Response(JSON.stringify(quotePayload({ last_price: 25001.25 })), { status: 200 });
  const response = await handleRequest(new Request("https://worker.test/v1/nifty/quote", {
    headers: { "CF-Connecting-IP": "192.0.2.13" },
  }), env);
  const bodyText = await response.text();
  assert.equal(bodyText.includes(env.UPSTOX_ANALYTICS_TOKEN), false);
});
