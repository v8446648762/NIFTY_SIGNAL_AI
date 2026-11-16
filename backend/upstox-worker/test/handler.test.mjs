import test from "node:test";
import assert from "node:assert/strict";
import { handleRequest } from "../dist/src/index.js";

const env = {
  UPSTOX_ANALYTICS_TOKEN: "test-token",
  GOOGLE_CLIENT_ID: "mgrow-test-client.apps.googleusercontent.com",
  MGROW_ALLOWED_GOOGLE_EMAIL: "owner@example.com",
  QUOTE_LIMITER: { limit: async () => ({ success: true }) },
};
const originalFetch = globalThis.fetch;
const keyPair = await crypto.subtle.generateKey(
  { name: "RSASSA-PKCS1-v1_5", modulusLength: 2048, publicExponent: new Uint8Array([1, 0, 1]), hash: "SHA-256" },
  true,
  ["sign", "verify"],
);
const publicJwk = await crypto.subtle.exportKey("jwk", keyPair.publicKey);
publicJwk.kid = "mgrow-test-key";
publicJwk.alg = "RS256";
publicJwk.use = "sig";

function encode(value) {
  const bytes = new TextEncoder().encode(JSON.stringify(value));
  let binary = "";
  for (const byte of bytes) binary += String.fromCharCode(byte);
  return btoa(binary).replace(/=/g, "").replace(/\+/g, "-").replace(/\//g, "_");
}

async function makeToken(overrides = {}) {
  const now = Math.floor(Date.now() / 1000);
  const header = encode({ alg: "RS256", typ: "JWT", kid: "mgrow-test-key" });
  const claims = encode({
    iss: "https://accounts.google.com",
    aud: env.GOOGLE_CLIENT_ID,
    exp: now + 3600,
    iat: now,
    email: "owner@example.com",
    email_verified: true,
    ...overrides,
  });
  const signingInput = `${header}.${claims}`;
  const signature = new Uint8Array(await crypto.subtle.sign(
    "RSASSA-PKCS1-v1_5", keyPair.privateKey, new TextEncoder().encode(signingInput)
  ));
  let binary = "";
  for (const byte of signature) binary += String.fromCharCode(byte);
  const encodedSignature = btoa(binary).replace(/=/g, "").replace(/\+/g, "-").replace(/\//g, "_");
  return `${signingInput}.${encodedSignature}`;
}

const validToken = await makeToken();
globalThis.fetch = async () => new Response(JSON.stringify({ keys: [publicJwk] }), {
  status: 200, headers: { "content-type": "application/json", "cache-control": "public, max-age=3600" }
});
// Warm the verifier's in-isolate Google signing-key cache before per-test upstream mocks.
const { verifyGoogleIdToken } = await import("../dist/src/google-auth.js");
assert.ok(await verifyGoogleIdToken(validToken, env));
globalThis.fetch = originalFetch;

test.afterEach(() => { globalThis.fetch = originalFetch; });

function quotePayload(quote = { last_price: 24123.5, instrument_token: "NSE_INDEX|Nifty 50" }, timestamp = new Date().toISOString()) {
  return { status: "success", data: { "NSE_INDEX:Nifty 50": { ...quote, timestamp } } };
}

function request(url, token = validToken, headers = {}) {
  return new Request(url, {
    headers: { ...(token ? { authorization: `Bearer ${token}` } : {}), ...headers },
  });
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
  const response = await handleRequest(new Request("https://worker.test/v1/other"), env);
  assert.equal(response.status, 404);
});

test("rejects missing bearer token without calling broker", async () => {
  globalThis.fetch = async () => { throw new Error("fetch should not be called"); };
  const response = await handleRequest(request("https://worker.test/v1/nifty/quote", null), env);
  assert.equal(response.status, 401);
});

test("rejects a Google token for a different account", async () => {
  const token = await makeToken({ email: "other@example.com" });
  globalThis.fetch = async () => { throw new Error("broker must not be called"); };
  const response = await handleRequest(request("https://worker.test/v1/nifty/quote", token), env);
  assert.equal(response.status, 401);
});

test("rejects a Google token for a different audience", async () => {
  const token = await makeToken({ aud: "another-client.apps.googleusercontent.com" });
  globalThis.fetch = async () => { throw new Error("broker must not be called"); };
  const response = await handleRequest(request("https://worker.test/v1/nifty/quote", token), env);
  assert.equal(response.status, 401);
});

test("returns 503 when required server secrets are missing", async () => {
  const response = await handleRequest(request("https://worker.test/v1/nifty/quote"), {});
  assert.equal(response.status, 503);
});

test("returns validated NIFTY quote and sends bearer token only upstream", async () => {
  let upstreamRequest;
  globalThis.fetch = async (url, init) => {
    upstreamRequest = { url: String(url), init };
    return new Response(JSON.stringify(quotePayload()), { status: 200, headers: { "content-type": "application/json" } });
  };
  const response = await handleRequest(request("https://worker.test/v1/nifty/quote"), env);
  assert.equal(response.status, 200);
  const body = await response.json();
  assert.equal(body.data.last_price, 24123.5);
  assert.ok(Number.isFinite(Date.parse(body.quoteTimestamp)));
  assert.match(upstreamRequest.init.headers.Authorization, /^Bearer test-token$/);
  assert.equal(response.headers.get("cache-control"), "no-store, max-age=0");
});

test("rejects malformed or non-positive quote payloads", async () => {
  globalThis.fetch = async () => new Response(JSON.stringify(quotePayload({ last_price: 0 })), { status: 200 });
  const response = await handleRequest(request("https://worker.test/v1/nifty/quote"), env);
  assert.equal(response.status, 502);
});

test("maps upstream failures to a safe error", async () => {
  globalThis.fetch = async () => new Response("private upstream detail", { status: 401 });
  const response = await handleRequest(request("https://worker.test/v1/nifty/quote"), env);
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
  const response = await handleRequest(request("https://worker.test/v1/nifty/quote"), env);
  assert.equal(response.status, 504);
  assert.deepEqual(await response.json(), { error: "upstream_timeout" });
});

test("does not expose broker token or Google ID token in a successful response", async () => {
  globalThis.fetch = async () => new Response(JSON.stringify(quotePayload({ last_price: 25001.25 })), { status: 200 });
  const response = await handleRequest(request("https://worker.test/v1/nifty/quote"), env);
  const bodyText = await response.text();
  assert.equal(bodyText.includes("test-token"), false);
  assert.equal(bodyText.includes(validToken), false);
  assert.equal(response.headers.get("cache-control"), "no-store, max-age=0");
});

test("does not expose broker response body when Upstox rejects the token", async () => {
  globalThis.fetch = async () => new Response("sensitive broker diagnostic", { status: 401 });
  const response = await handleRequest(request("https://worker.test/v1/nifty/quote"), env);
  const bodyText = await response.text();
  assert.equal(bodyText.includes("sensitive broker diagnostic"), false);
  assert.equal(bodyText.includes("test-token"), false);
  assert.equal(response.headers.get("cache-control"), "no-store, max-age=0");
});

test("ignores caller-supplied instrument query and requests only fixed NIFTY 50", async () => {
  let requestedUrl = "";
  globalThis.fetch = async (url) => {
    requestedUrl = String(url);
    return new Response(JSON.stringify(quotePayload({ last_price: 25001.25 })), { status: 200 });
  };
  const response = await handleRequest(request("https://worker.test/v1/nifty/quote?instrument_key=UNSAFE%7COTHER"), env);
  assert.equal(response.status, 200);
  assert.match(requestedUrl, /instrument_key=NSE_INDEX%7CNifty%2050/);
  assert.equal(requestedUrl.includes("UNSAFE"), false);
});


test("rejects quote access when rate limiter is not configured", async () => {
  globalThis.fetch = async () => { throw new Error("broker must not be called"); };
  const response = await handleRequest(request("https://worker.test/v1/nifty/quote"), {
    UPSTOX_ANALYTICS_TOKEN: env.UPSTOX_ANALYTICS_TOKEN,
    GOOGLE_CLIENT_ID: env.GOOGLE_CLIENT_ID,
    MGROW_ALLOWED_GOOGLE_EMAIL: env.MGROW_ALLOWED_GOOGLE_EMAIL,
  });
  assert.equal(response.status, 503);
  assert.deepEqual(await response.json(), { error: "rate_limiter_not_configured" });
});

test("blocks a request when the rate limiter denies it", async () => {
  let brokerCalled = false;
  globalThis.fetch = async () => { brokerCalled = true; throw new Error("broker must not be called"); };
  const limitedEnv = { ...env, QUOTE_LIMITER: { limit: async ({ key }) => {
    assert.equal(key, "owner@example.com");
    return { success: false };
  } } };
  const response = await handleRequest(request("https://worker.test/v1/nifty/quote"), limitedEnv);
  assert.equal(response.status, 429);
  assert.equal(response.headers.get("retry-after"), "60");
  assert.equal(brokerCalled, false);
  assert.deepEqual(await response.json(), { error: "rate_limit_exceeded" });
});

test("fails closed when the rate limiter service throws", async () => {
  globalThis.fetch = async () => { throw new Error("broker must not be called"); };
  const brokenEnv = { ...env, QUOTE_LIMITER: { limit: async () => { throw new Error("limiter unavailable"); } } };
  const response = await handleRequest(request("https://worker.test/v1/nifty/quote"), brokenEnv);
  assert.equal(response.status, 503);
  assert.deepEqual(await response.json(), { error: "rate_limiter_unavailable" });
});


test("rejects stale quote timestamps", async () => {
  globalThis.fetch = async () => new Response(JSON.stringify(quotePayload({ last_price: 25001.25 }, new Date(Date.now() - 10 * 60_000).toISOString())), { status: 200 });
  const response = await handleRequest(request("https://worker.test/v1/nifty/quote"), env);
  assert.equal(response.status, 502);
  assert.deepEqual(await response.json(), { error: "invalid_upstream_payload" });
});

test("rejects quote timestamps too far in the future", async () => {
  globalThis.fetch = async () => new Response(JSON.stringify(quotePayload({ last_price: 25001.25 }, new Date(Date.now() + 60_000).toISOString())), { status: 200 });
  const response = await handleRequest(request("https://worker.test/v1/nifty/quote"), env);
  assert.equal(response.status, 502);
  assert.deepEqual(await response.json(), { error: "invalid_upstream_payload" });
});

test("rejects an ID token issued more than one hour ago", async () => {
  const now = Math.floor(Date.now() / 1000);
  const token = await makeToken({ iat: now - 3700, exp: now + 100 });
  assert.equal(await verifyGoogleIdToken(token, env), null);
});

test("rejects an ID token with an excessive lifetime", async () => {
  const now = Math.floor(Date.now() / 1000);
  const token = await makeToken({ iat: now, exp: now + 7200 });
  assert.equal(await verifyGoogleIdToken(token, env), null);
});

test("requires the authorized presenter for multiple audiences", async () => {
  const token = await makeToken({ aud: [env.GOOGLE_CLIENT_ID, "another-client.apps.googleusercontent.com"] });
  assert.equal(await verifyGoogleIdToken(token, env), null);
});

test("accepts multiple audiences only when azp matches this client", async () => {
  const token = await makeToken({
    aud: [env.GOOGLE_CLIENT_ID, "another-client.apps.googleusercontent.com"],
    azp: env.GOOGLE_CLIENT_ID,
  });
  assert.ok(await verifyGoogleIdToken(token, env));
});
