package com.tulipskun.droidime

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

object CloudflareProvisioner {
    private const val API = "https://api.cloudflare.com/client/v4"
    private const val ACCOUNT_ID = "3b953d1cbb784b3424c27798931bc37b"
    private const val ZONE_ID = "0df5ea46806f151750ec01be67bf365b"
    private const val KV_NAMESPACE_ID = "9c094dd95b96473085eaaa625aefc383"
    private const val ACCESS_SERVICE_TOKEN_ID = "f3d3095e-54c7-4dbc-b2d1-0b5e4a8b12a7"
    private const val ORIGIN_SUFFIX = "aixodia.dpdns.org"

    data class Result(val tunnelId: String, val tunnelToken: String, val hostname: String)

    fun provision(context: Context, apiToken: String, deviceName: String): Result {
        require(apiToken.isNotBlank()) { "Cloudflare API token is required" }
        require(DeviceEndpoint.isValid(deviceName)) { "Invalid device name" }
        val appContext = context.applicationContext
        val prefs = appContext.getSharedPreferences("droid_mcp", Context.MODE_PRIVATE)
        val tunnelName = "droidime-$deviceName"
        val hostname = "$deviceName.$ORIGIN_SUFFIX"

        var tunnelId = prefs.getString("cf_tunnel_id", null)
            ?.takeIf { prefs.getString("cf_tunnel_device_name", null) == deviceName }
        var tunnelToken: String? = null
        val cachedTunnelId = tunnelId
        if (cachedTunnelId != null) {
            tunnelToken = try {
                getTunnelToken(apiToken, cachedTunnelId)
            } catch (_: Throwable) {
                tunnelId = null
                null
            }
        }
        if (tunnelId == null) {
            val existing = apiRequest(apiToken, "GET", "/accounts/$ACCOUNT_ID/cfd_tunnel")
                .getJSONArray("result")
            for (index in 0 until existing.length()) {
                val item = existing.getJSONObject(index)
                if (item.optString("name") != tunnelName || !item.isNull("deleted_at")) continue
                val foundTunnelId = item.optString("id").takeIf(String::isNotBlank)
                if (foundTunnelId != null) {
                    tunnelId = foundTunnelId
                    tunnelToken = getTunnelToken(apiToken, foundTunnelId)
                    break
                }
            }
        }
        if (tunnelId == null) {
            val created = apiRequest(
                apiToken,
                "POST",
                "/accounts/$ACCOUNT_ID/cfd_tunnel",
                JSONObject().put("name", tunnelName).put("config_src", "cloudflare")
            ).getJSONObject("result")
            val createdTunnelId = created.optString("id").takeIf(String::isNotBlank)
                ?: throw IllegalStateException("Cloudflare did not return a Tunnel ID")
            tunnelId = createdTunnelId
            tunnelToken = created.optString("token").takeIf(String::isNotBlank)
                ?: getTunnelToken(apiToken, createdTunnelId)
        }
        val resolvedTunnelId = tunnelId ?: throw IllegalStateException("Cloudflare Tunnel ID is missing")
        val resolvedTunnelToken = tunnelToken ?: getTunnelToken(apiToken, resolvedTunnelId)
        prefs.edit()
            .putString("cf_tunnel_id", resolvedTunnelId)
            .putString("cf_tunnel_device_name", deviceName)
            .apply()

        apiRequest(
            apiToken,
            "PUT",
            "/accounts/$ACCOUNT_ID/cfd_tunnel/$resolvedTunnelId/configurations",
            JSONObject().put(
                "config",
                JSONObject().put(
                    "ingress",
                    JSONArray()
                        .put(JSONObject().put("hostname", hostname)
                            .put("service", "http://localhost:8787")
                            .put("originRequest", JSONObject()))
                        .put(JSONObject().put("service", "http_status:404"))
                )
            )
        )

        ensureAccessProtection(apiToken, hostname)
        ensureDnsRecord(apiToken, hostname, "$resolvedTunnelId.cfargotunnel.com")
        apiRequest(
            apiToken,
            "PUT",
            "/accounts/$ACCOUNT_ID/storage/kv/namespaces/$KV_NAMESPACE_ID/values/${encode(deviceName)}",
            rawBody = hostname.toByteArray(Charsets.UTF_8),
            contentType = "text/plain; charset=utf-8"
        )

        return Result(resolvedTunnelId, resolvedTunnelToken, hostname)
    }

    private fun getTunnelToken(apiToken: String, tunnelId: String): String {
        val response = apiRequest(apiToken, "GET", "/accounts/$ACCOUNT_ID/cfd_tunnel/$tunnelId/token")
        return response.optString("result").takeIf(String::isNotBlank)
            ?: throw IllegalStateException("Cloudflare returned an empty Tunnel token")
    }

    private fun ensureAccessProtection(apiToken: String, hostname: String) {
        val apps = apiRequest(apiToken, "GET", "/accounts/$ACCOUNT_ID/access/apps")
            .getJSONArray("result")
        var appId: String? = null
        for (index in 0 until apps.length()) {
            val app = apps.getJSONObject(index)
            if (app.optString("domain") == hostname) {
                appId = app.optString("id").takeIf(String::isNotBlank)
                break
            }
        }
        if (appId == null) {
            val created = apiRequest(
                apiToken,
                "POST",
                "/accounts/$ACCOUNT_ID/access/apps",
                JSONObject()
                    .put("name", "DroidIME MCP origin - " + hostname.substringBefore('.'))
                    .put("type", "self_hosted")
                    .put("domain", hostname)
            ).getJSONObject("result")
            appId = created.optString("id").takeIf(String::isNotBlank)
                ?: throw IllegalStateException("Cloudflare Access did not return an application ID")
        }

        val policies = apiRequest(apiToken, "GET", "/accounts/$ACCOUNT_ID/access/apps/$appId/policies")
            .getJSONArray("result")
        var protected = false
        for (index in 0 until policies.length()) {
            val policy = policies.getJSONObject(index)
            val include = policy.optJSONArray("include") ?: continue
            for (ruleIndex in 0 until include.length()) {
                val serviceToken = include.optJSONObject(ruleIndex)?.optJSONObject("service_token")
                if (serviceToken?.optString("token_id") == ACCESS_SERVICE_TOKEN_ID &&
                    policy.optString("decision") == "non_identity") {
                    protected = true
                    break
                }
            }
            if (protected) break
        }
        if (!protected) {
            apiRequest(
                apiToken,
                "POST",
                "/accounts/$ACCOUNT_ID/access/apps/$appId/policies",
                JSONObject()
                    .put("name", "DroidIME gateway service token only")
                    .put("decision", "non_identity")
                    .put("include", JSONArray().put(
                        JSONObject().put("service_token", JSONObject().put("token_id", ACCESS_SERVICE_TOKEN_ID))
                    ))
            )
        }
    }

    private fun ensureDnsRecord(apiToken: String, hostname: String, target: String) {
        val query = "type=CNAME&name=${encode(hostname)}"
        val records = apiRequest(apiToken, "GET", "/zones/$ZONE_ID/dns_records?$query")
            .getJSONArray("result")
        val body = JSONObject()
            .put("type", "CNAME")
            .put("name", hostname)
            .put("content", target)
            .put("proxied", true)
            .put("ttl", 1)
        if (records.length() > 0) {
            val recordId = records.getJSONObject(0).optString("id")
            if (recordId.isBlank()) throw IllegalStateException("Cloudflare DNS record has no ID")
            apiRequest(apiToken, "PUT", "/zones/$ZONE_ID/dns_records/$recordId", body)
        } else {
            apiRequest(apiToken, "POST", "/zones/$ZONE_ID/dns_records", body)
        }
    }

    private fun apiRequest(
        token: String,
        method: String,
        path: String,
        body: JSONObject? = null,
        rawBody: ByteArray? = null,
        contentType: String = "application/json"
    ): JSONObject {
        val connection = (URL(API + path).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 20000
            readTimeout = 30000
            setRequestProperty("Authorization", "Bearer $token")
            setRequestProperty("Accept", "application/json")
            if (body != null || rawBody != null) {
                doOutput = true
                setRequestProperty("Content-Type", contentType)
            }
        }
        try {
            if (body != null || rawBody != null) {
                connection.outputStream.use { output ->
                    output.write(rawBody ?: body!!.toString().toByteArray(Charsets.UTF_8))
                }
            }
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val bytes = ByteArrayOutputStream().use { output ->
                stream?.use { it.copyTo(output) }
                output.toByteArray()
            }
            val response = if (bytes.isNotEmpty()) JSONObject(String(bytes, Charsets.UTF_8)) else JSONObject()
            if (status !in 200..299 || !response.optBoolean("success", false)) {
                val errors = response.optJSONArray("errors")
                val detail = if (errors != null && errors.length() > 0) {
                    errors.optJSONObject(0)?.optString("message").orEmpty()
                } else {
                    "HTTP $status"
                }
                throw IllegalStateException("Cloudflare API: " + detail.take(220))
            }
            return response
        } finally {
            connection.disconnect()
        }
    }

    private fun encode(value: String): String =
        URLEncoder.encode(value, "UTF-8").replace("+", "%20")
}
