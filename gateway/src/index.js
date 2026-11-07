const DEVICE_NAME = /^[a-z0-9](?:[a-z0-9-]{0,30}[a-z0-9])?$/;
const ORIGIN_HOST = /^[a-z0-9](?:[a-z0-9-]{0,30}[a-z0-9])?\.aixodia\.dpdns\.org$/;
const HOP_BY_HOP = new Set(["connection", "keep-alive", "proxy-authenticate", "proxy-authorization", "te", "trailer", "transfer-encoding", "upgrade", "host"]);

function error(status, message) {
  return new Response(JSON.stringify({ error: message }), {
    status,
    headers: { "content-type": "application/json; charset=utf-8", "cache-control": "no-store" }
  });
}

export default {
  async fetch(request, env) {
    const url = new URL(request.url);
    const device = url.pathname.split("/").filter(Boolean)[0] || "";
    if (!DEVICE_NAME.test(device)) return error(404, "Unknown device");
    if (!env.DEVICE_TUNNELS) return error(503, "Gateway is not configured");
    const origin = await env.DEVICE_TUNNELS.get(device);
    if (!origin || !ORIGIN_HOST.test(origin)) return error(404, "Unknown device");
    if (!env.ACCESS_CLIENT_ID || !env.ACCESS_CLIENT_SECRET) return error(503, "Origin authentication is not configured");

    const headers = new Headers(request.headers);
    for (const name of HOP_BY_HOP) headers.delete(name);
    headers.set("CF-Access-Client-Id", env.ACCESS_CLIENT_ID);
    headers.set("CF-Access-Client-Secret", env.ACCESS_CLIENT_SECRET);
    headers.set("X-Forwarded-Host", url.host);
    headers.set("X-Forwarded-Proto", "https");
    const target = new URL(url.pathname + url.search, "https://" + origin);
    const upstreamRequest = new Request(target, {
      method: request.method, headers,
      body: request.method === "GET" || request.method === "HEAD" ? undefined : await request.arrayBuffer(),
      redirect: "manual"
    });
    try {
      const response = await fetch(upstreamRequest);
      const responseHeaders = new Headers(response.headers);
      for (const name of HOP_BY_HOP) responseHeaders.delete(name);
      responseHeaders.set("cache-control", "no-store");
      return new Response(response.body, { status: response.status, statusText: response.statusText, headers: responseHeaders });
    } catch {
      return error(502, "Device tunnel is unavailable");
    }
  }
};
export { DEVICE_NAME, ORIGIN_HOST };
