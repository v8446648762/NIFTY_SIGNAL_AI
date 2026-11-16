import test from "node:test";
import assert from "node:assert/strict";
import { handleRequest } from "../dist/src/index.js";

const emptyEnv = {
  UPSTOX_ANALYTICS_TOKEN: "",
  GOOGLE_CLIENT_ID: "",
  MGROW_ALLOWED_GOOGLE_EMAIL: "",
  QUOTE_LIMITER: { limit: async () => ({ success: true }) },
};

test("GET /health reports worker status without claiming broker connection", async () => {
  const response = await handleRequest(new Request("https://worker.example/health"), emptyEnv);
  assert.equal(response.status, 200);
  assert.deepEqual(await response.json(), {
    service: "mgrow-upstox-proxy",
    status: "ready",
    brokerConnected: false,
  });
  assert.equal(response.headers.get("cache-control"), "no-store, max-age=0");
});

test("non-GET requests are rejected", async () => {
  const response = await handleRequest(
    new Request("https://worker.example/v1/nifty/quote", { method: "POST" }),
    emptyEnv,
  );
  assert.equal(response.status, 405);
  assert.deepEqual(await response.json(), { error: "method_not_allowed" });
});

test("unknown paths are rejected", async () => {
  const response = await handleRequest(new Request("https://worker.example/unknown"), emptyEnv);
  assert.equal(response.status, 404);
  assert.deepEqual(await response.json(), { error: "not_found" });
});

test("quote endpoint fails closed when server secrets are missing", async () => {
  const response = await handleRequest(
    new Request("https://worker.example/v1/nifty/quote"),
    emptyEnv,
  );
  assert.equal(response.status, 503);
  assert.deepEqual(await response.json(), { error: "server_secrets_not_configured" });
});

test("quote endpoint rejects missing bearer credentials", async () => {
  const configuredEnv = {
    ...emptyEnv,
    UPSTOX_ANALYTICS_TOKEN: "server-only-test-token",
    GOOGLE_CLIENT_ID: "test-client-id",
    MGROW_ALLOWED_GOOGLE_EMAIL: "owner@example.com",
  };
  const response = await handleRequest(
    new Request("https://worker.example/v1/nifty/quote"),
    configuredEnv,
  );
  assert.equal(response.status, 401);
  assert.deepEqual(await response.json(), { error: "unauthorized" });
});

test("quote endpoint rejects malformed bearer tokens", async () => {
  const configuredEnv = {
    ...emptyEnv,
    UPSTOX_ANALYTICS_TOKEN: "server-only-test-token",
    GOOGLE_CLIENT_ID: "test-client-id",
    MGROW_ALLOWED_GOOGLE_EMAIL: "owner@example.com",
  };
  const response = await handleRequest(
    new Request("https://worker.example/v1/nifty/quote", {
      headers: { Authorization: "Bearer not-a-valid-jwt" },
    }),
    configuredEnv,
  );
  assert.equal(response.status, 401);
  assert.deepEqual(await response.json(), { error: "unauthorized" });
});
