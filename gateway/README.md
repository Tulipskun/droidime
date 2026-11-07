# AIxodia MCP Gateway

Public MCP URLs use https://mcp.aixodia.dpdns.org/<device-name>. The Worker looks up the first path segment in the DEVICE_TUNNELS KV namespace and proxies the request to that device's dedicated Named Tunnel origin hostname. The original path and query are preserved.

## Required Cloudflare resources

- Zone dpdns.org on the same Cloudflare account.
- Worker route mcp.aixodia.dpdns.org/*.
- KV namespace bound as DEVICE_TUNNELS.
- One Named Tunnel per device, each with unique origin hostname: <device-name>.aixodia.dpdns.org.
- KV entry: key <device-name>, value <device-name>.aixodia.dpdns.org.
- Cloudflare Access application/policy protecting mcp.aixodia.dpdns.org/* for MCP clients.
- A separate Access application protecting *.aixodia.dpdns.org/*, with a service-token policy. Add that service token to the Worker as ACCESS_CLIENT_ID and ACCESS_CLIENT_SECRET secrets.

The Worker fails closed if origin service-token credentials are missing. Do not expose origin tunnel hostnames without Access protection.

## Deploy

Replace REPLACE_WITH_KV_NAMESPACE_ID in wrangler.toml. Authenticate Wrangler using a Cloudflare API token with Workers Scripts edit, Workers Routes edit, Workers KV edit, and zone read permissions for dpdns.org.

    npm test
    npx wrangler kv namespace create DEVICE_TUNNELS
    npx wrangler secret put ACCESS_CLIENT_ID
    npx wrangler secret put ACCESS_CLIENT_SECRET
    npx wrangler deploy
    npx wrangler kv key put --binding=DEVICE_TUNNELS pixel-8 pixel-8.mcp-origin.aixodia.dpdns.org

For each Android device, create a dedicated remotely-managed Named Tunnel. Configure its public hostname as `<device-name>.aixodia.dpdns.org`, routing to `http://localhost:8787`, and protect that hostname with a Cloudflare Access service-token policy. Enter the tunnel connector token in DroidIME. The public MCP URL remains `https://mcp.aixodia.dpdns.org/<device-name>`. The tunnel connector token is not the Cloudflare API token and cannot deploy this Worker or create DNS/Access resources.
