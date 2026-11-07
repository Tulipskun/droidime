import test from "node:test";
import assert from "node:assert/strict";
import worker, { DEVICE_NAME, ORIGIN_HOST } from "../src/index.js";

test("validates device names", () => {
  for (const value of ["pixel-8", "phone1", "a"]) assert.equal(DEVICE_NAME.test(value), true);
  for (const value of ["", "-phone", "phone-", "../etc", "a/b", "UPPER", "a".repeat(33)]) assert.equal(DEVICE_NAME.test(value), false);
});

test("restricts upstream hostname suffix", () => {
  assert.equal(ORIGIN_HOST.test("pixel-8.mcp-origin.aixodia.dpdns.org"), true);
  assert.equal(ORIGIN_HOST.test("attacker.example"), false);
  assert.equal(ORIGIN_HOST.test("pixel-8.example.org"), false);
});

test("fails closed without origin authentication", async () => {
  const response = await worker.fetch(new Request("https://mcp.aixodia.dpdns.org/pixel-8"), {
    DEVICE_TUNNELS: { get: async () => "pixel-8.mcp-origin.aixodia.dpdns.org" }
  });
  assert.equal(response.status, 503);
});

test("proxies MCP path, query, method, body and Access service token", async () => {
  let seen;
  const originalFetch = globalThis.fetch;
  globalThis.fetch = async request => { seen = request; return new Response("ok", { status: 200 }); };
  try {
    const request = new Request("https://mcp.aixodia.dpdns.org/pixel-8?x=1", {
      method: "POST", headers: { "content-type": "application/json", connection: "keep-alive" },
      body: JSON.stringify({ jsonrpc: "2.0" })
    });
    const response = await worker.fetch(request, {
      DEVICE_TUNNELS: { get: async key => key === "pixel-8" ? "pixel-8.mcp-origin.aixodia.dpdns.org" : null },
      ACCESS_CLIENT_ID: "test-id", ACCESS_CLIENT_SECRET: "test-secret"
    });
    assert.equal(response.status, 200);
    assert.equal(await response.text(), "ok");
    assert.equal(seen.url, "https://pixel-8.mcp-origin.aixodia.dpdns.org/pixel-8?x=1");
    assert.equal(seen.headers.get("cf-access-client-id"), "test-id");
    assert.equal(seen.headers.get("cf-access-client-secret"), "test-secret");
    assert.equal(seen.headers.get("connection"), null);
    assert.deepEqual(await seen.json(), { jsonrpc: "2.0" });
  } finally { globalThis.fetch = originalFetch; }
});

test("returns 404 for unknown devices", async () => {
  const response = await worker.fetch(new Request("https://mcp.aixodia.dpdns.org/nope"), {
    DEVICE_TUNNELS: { get: async () => null }, ACCESS_CLIENT_ID: "id", ACCESS_CLIENT_SECRET: "secret"
  });
  assert.equal(response.status, 404);
});
