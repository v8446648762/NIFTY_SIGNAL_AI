import test from "node:test";
import assert from "node:assert/strict";
import { handleRequest } from "../dist/src/index.js";

const env = {
  UPSTOX_ANALYTICS_TOKEN: "test-token",
  QUOTE_LIMITER: { limit: async () => ({ success: true }) },
};
const originalFetch = globalThis.fetch;
test.afterEach(() => { globalThis.fetch = originalFetch; });

function quotePayload(quote = { last_price: 24123.5, instrument_token: "NSE_INDEX|Nifty 50" }, timestamp = new Date().toISOString()) {
  return { status: "success", data: { "NSE_INDEX:Nifty 50": { ...quote, timestamp } } };
}
function request(url, headers = {}) {
  return new Request(url, { headers: { "CF-Connecting-IP": "203.0.113.10", ...headers } });
}

test("health endpoint is public and does not call broker", async () => {
  globalThis.fetch = async () => { throw new Error("fetch should not be called"); };
  const response = await handleRequest(new Request("https://worker.test/health"), env);
  assert.equal(response.status, 200);
  assert.equal((await response.json()).brokerConnected, false);
});
test("rejects unsupported methods", async () => {
  const response = await handleRequest(new Request("https://worker.test/v1/nifty/quote", { method: "POST" }), env);
  assert.equal(response.status, 405);
});
test("rejects unknown routes", async () => {
  const response = await handleRequest(request("https://worker.test/v1/other"), env);
  assert.equal(response.status, 404);
});
test("returns 503 when Upstox server secret is missing", async () => {
  const response = await handleRequest(request("https://worker.test/v1/nifty/quote"), { ...env, UPSTOX_ANALYTICS_TOKEN: "" });
  assert.equal(response.status, 503);
});
test("requires Cloudflare client IP for rate limiting", async () => {
  const response = await handleRequest(new Request("https://worker.test/v1/nifty/quote"), env);
  assert.equal(response.status, 503);
  assert.deepEqual(await response.json(), { error: "client_ip_unavailable" });
});
test("returns validated NIFTY quote and sends broker token only upstream", async () => {
  let upstreamRequest;
  globalThis.fetch = async (url, init) => {
    upstreamRequest = { url: String(url), init };
    return new Response(JSON.stringify(quotePayload()), { status: 200 });
  };
  const response = await handleRequest(request("https://worker.test/v1/nifty/quote"), env);
  assert.equal(response.status, 200);
  assert.equal((await response.json()).data.last_price, 24123.5);
  assert.match(upstreamRequest.init.headers.Authorization, /^Bearer test-token$/);
  assert.equal(response.headers.get("cache-control"), "no-store, max-age=0");
});
test("rejects malformed quote payload", async () => {
  globalThis.fetch = async () => new Response(JSON.stringify(quotePayload({ last_price: 0 })), { status: 200 });
  const response = await handleRequest(request("https://worker.test/v1/nifty/quote"), env);
  assert.equal(response.status, 502);
});
test("rate limit is keyed by client IP and blocks broker request", async () => {
  let brokerCalled = false;
  globalThis.fetch = async () => { brokerCalled = true; throw new Error("must not call broker"); };
  const limitedEnv = { ...env, QUOTE_LIMITER: { limit: async ({ key }) => { assert.equal(key, "203.0.113.10"); return { success: false }; } } };
  const response = await handleRequest(request("https://worker.test/v1/nifty/quote"), limitedEnv);
  assert.equal(response.status, 429);
  assert.equal(response.headers.get("retry-after"), "60");
  assert.equal(brokerCalled, false);
});
test("fails closed when rate limiter throws", async () => {
  const brokenEnv = { ...env, QUOTE_LIMITER: { limit: async () => { throw new Error("limiter unavailable"); } } };
  const response = await handleRequest(request("https://worker.test/v1/nifty/quote"), brokenEnv);
  assert.equal(response.status, 503);
});
test("ignores caller-supplied instrument and requests fixed NIFTY 50", async () => {
  let requestedUrl = "";
  globalThis.fetch = async (url) => { requestedUrl = String(url); return new Response(JSON.stringify(quotePayload()), { status: 200 }); };
  const response = await handleRequest(request("https://worker.test/v1/nifty/quote?instrument_key=UNSAFE"));
  assert.equal(response.status, 200);
  assert.match(requestedUrl, /instrument_key=NSE_INDEX%7CNifty%2050/);
  assert.equal(requestedUrl.includes("UNSAFE"), false);
});
