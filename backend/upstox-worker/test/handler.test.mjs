import test from "node:test";
import assert from "node:assert/strict";
import { handleRequest } from "../dist/src/index.js";

const env = { UPSTOX_ANALYTICS_TOKEN: "test-token", MGROW_CLIENT_KEY: "test-client-key" };
const originalFetch = globalThis.fetch;

test.afterEach(() => { globalThis.fetch = originalFetch; });

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
  const response = await handleRequest(new Request("https://worker.test/v1/other"), env);
  assert.equal(response.status, 404);
});

test("rejects missing or incorrect client key without calling broker", async () => {
  globalThis.fetch = async () => { throw new Error("fetch should not be called"); };
  const response = await handleRequest(new Request("https://worker.test/v1/nifty/quote"), env);
  assert.equal(response.status, 401);
});

test("returns 503 when server secrets are missing", async () => {
  const response = await handleRequest(new Request("https://worker.test/v1/nifty/quote", {
    headers: { "x-mgrow-client-key": "test-client-key" }
  }), {});
  assert.equal(response.status, 503);
});

test("returns validated NIFTY quote and sends bearer token only upstream", async () => {
  let upstreamRequest;
  globalThis.fetch = async (url, init) => {
    upstreamRequest = { url: String(url), init };
    return new Response(JSON.stringify({
      status: "success",
      data: { "NSE_INDEX|Nifty 50": { last_price: 24123.5, instrument_token: 123 } }
    }), { status: 200, headers: { "content-type": "application/json" } });
  };
  const response = await handleRequest(new Request("https://worker.test/v1/nifty/quote", {
    headers: { "x-mgrow-client-key": "test-client-key" }
  }), env);
  assert.equal(response.status, 200);
  const body = await response.json();
  assert.equal(body.data.last_price, 24123.5);
  assert.match(upstreamRequest.init.headers.Authorization, /^Bearer test-token$/);
  assert.equal(response.headers.get("cache-control"), "no-store, max-age=0");
});

test("rejects malformed or non-positive quote payloads", async () => {
  globalThis.fetch = async () => new Response(JSON.stringify({
    status: "success", data: { "NSE_INDEX|Nifty 50": { last_price: 0 } }
  }), { status: 200 });
  const response = await handleRequest(new Request("https://worker.test/v1/nifty/quote", {
    headers: { "x-mgrow-client-key": "test-client-key" }
  }), env);
  assert.equal(response.status, 502);
});

test("maps upstream failures to a safe error", async () => {
  globalThis.fetch = async () => new Response("private upstream detail", { status: 401 });
  const response = await handleRequest(new Request("https://worker.test/v1/nifty/quote", {
    headers: { "x-mgrow-client-key": "test-client-key" }
  }), env);
  assert.equal(response.status, 502);
  assert.deepEqual(await response.json(), { error: "upstream_market_data_error", upstreamStatus: 401 });
});


test("times out when Upstox does not respond", async () => {
  globalThis.fetch = async (_url, init) => new Promise((resolve, reject) => {
    init.signal.addEventListener("abort", () => {
      const error = new Error("aborted");
      error.name = "AbortError";
      reject(error);
    }, { once: true });
  });
  const response = await handleRequest(new Request("https://worker.test/v1/nifty/quote", {
    headers: { "x-mgrow-client-key": "test-client-key" }
  }), env);
  assert.equal(response.status, 504);
  assert.deepEqual(await response.json(), { error: "upstream_timeout" });
});


test("does not expose broker token or client key in a successful response", async () => {
  globalThis.fetch = async () => new Response(JSON.stringify({
    status: "success",
    data: { "NSE_INDEX|Nifty 50": { last_price: 25001.25 } }
  }), { status: 200 });
  const response = await handleRequest(new Request("https://worker.test/v1/nifty/quote", {
    headers: { "x-mgrow-client-key": "test-client-key" }
  }), env);
  const bodyText = await response.text();
  assert.equal(bodyText.includes("test-token"), false);
  assert.equal(bodyText.includes("test-client-key"), false);
  assert.equal(response.headers.get("cache-control"), "no-store, max-age=0");
});

test("does not expose broker response body when Upstox rejects the token", async () => {
  globalThis.fetch = async () => new Response("sensitive broker diagnostic", { status: 401 });
  const response = await handleRequest(new Request("https://worker.test/v1/nifty/quote", {
    headers: { "x-mgrow-client-key": "test-client-key" }
  }), env);
  const bodyText = await response.text();
  assert.equal(bodyText.includes("sensitive broker diagnostic"), false);
  assert.equal(bodyText.includes("test-token"), false);
  assert.equal(response.headers.get("cache-control"), "no-store, max-age=0");
});

test("ignores caller-supplied instrument query and requests only fixed NIFTY 50", async () => {
  let requestedUrl = "";
  globalThis.fetch = async (url) => {
    requestedUrl = String(url);
    return new Response(JSON.stringify({
      status: "success",
      data: { "NSE_INDEX|Nifty 50": { last_price: 25001.25 } }
    }), { status: 200 });
  };
  const response = await handleRequest(new Request(
    "https://worker.test/v1/nifty/quote?instrument_key=UNSAFE%7COTHER",
    { headers: { "x-mgrow-client-key": "test-client-key" } }
  ), env);
  assert.equal(response.status, 200);
  assert.match(requestedUrl, /instrument_key=NSE_INDEX%7CNifty%2050/);
  assert.equal(requestedUrl.includes("UNSAFE"), false);
});
