package com.tulipskun.droidime

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.net.Inet4Address
import java.net.InetAddress
import java.util.concurrent.Executors

class CloudflareTunnelService : Service() {
    private val executor = Executors.newSingleThreadExecutor()
    @Volatile private var process: Process? = null
    @Volatile private var stopping = false

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, notification("Starting Named Tunnel"))
        executor.execute {
            try {
                ensureMcpServer()
                val deviceName = DeviceEndpoint.getOrCreateName(this)
                require(DeviceEndpoint.isValid(deviceName)) { "Enter a valid device name" }
                val prefs = getSharedPreferences("droid_mcp", MODE_PRIVATE)
                val apiToken = CloudflareApiTokenStore.read(this)
                val savedToken = TunnelTokenStore.read(this)
                val managedId = prefs.getString("cf_tunnel_id", null)
                val managedName = prefs.getString("cf_tunnel_device_name", null)
                val tokenDeviceName = prefs.getString("tunnel_token_device_name", null)
                val isApiManaged = !managedId.isNullOrBlank() &&
                    managedName == deviceName && tokenDeviceName == deviceName
                val token = if (!apiToken.isNullOrBlank() && (!isApiManaged || savedToken.isNullOrBlank())) {
                    val result = CloudflareProvisioner.provision(this, apiToken, deviceName)
                    TunnelTokenStore.save(this, result.tunnelToken)
                    prefs.edit()
                        .putString("tunnel_token_device_name", deviceName)
                        .putString("tunnel_status", "Cloudflare resources configured")
                        .remove("tunnel_error")
                        .apply()
                    result.tunnelToken
                } else {
                    savedToken ?: throw IllegalStateException("Save CF_TOKEN to provision the Named Tunnel")
                }
                startTunnel(token, deviceName)
            } catch (error: Throwable) {
                val message = error.message ?: "Cloudflare Named Tunnel failed"
                Log.e(LOG_TAG, "Tunnel startup failed: " + redact(message), error)
                if (!stopping) setState(false, null, message)
                stopSelf()
            }
        }
    }

    private fun ensureMcpServer() {
        if (McpService.isRunning) return
        val intent = Intent(this, McpService::class.java)
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(intent) else startService(intent)
        repeat(50) {
            if (McpService.isRunning) return
            Thread.sleep(100)
        }
        if (!McpService.isRunning) throw IllegalStateException("MCP server could not be started")
    }

    private fun startTunnel(token: String, deviceName: String) {
        val binary = TermuxCloudflaredInstaller.ensureInstalled(this)
        val home = File(filesDir, "cloudflared-home").apply { mkdirs() }
        val edgeAddresses = resolveEdgeAddresses()
        Log.i(LOG_TAG, "Android DNS resolved Cloudflare edge addresses: ${edgeAddresses.joinToString()}")
        val command = mutableListOf(
            binary.absolutePath,
            "tunnel",
            "--no-autoupdate",
            "--protocol", "http2",
            "--edge-ip-version", "4",
            "--metrics", "127.0.0.1:${CloudflareTunnelConfig.METRICS_PORT}"
        )
        edgeAddresses.forEach { address ->
            command.add("--edge")
            command.add(address)
        }
        command.addAll(listOf("run", "--token", token))
        val started = ProcessBuilder(command).redirectErrorStream(true).apply {
            environment()["HOME"] = home.absolutePath
            environment()["TMPDIR"] = cacheDir.absolutePath
            environment()["GODEBUG"] = "netdns=cgo"
        }.start()
        process = started
        val endpoint = DeviceEndpoint.endpoint(deviceName)
        setState(false, endpoint, null)
        val recent = ArrayDeque<String>()
        BufferedReader(InputStreamReader(started.inputStream)).use { reader ->
            while (!stopping) {
                val line = reader.readLine() ?: break
                if (line.isNotBlank()) {
                    val safeLine = redact(line).take(500)
                    recent.addLast(safeLine)
                    while (recent.size > MAX_OUTPUT_LINES) recent.removeFirst()
                    Log.i(LOG_TAG, safeLine)
                    val normalized = line.lowercase()
                    if (normalized.contains("registered tunnel connection") ||
                        normalized.contains("connection registered")) {
                        setState(true, endpoint, null)
                    } else if (normalized.contains("unable to establish") ||
                        normalized.contains("failed to connect") ||
                        normalized.contains("invalid tunnel")) {
                        setState(false, null, line.take(MAX_ERROR_CHARS))
                    }
                }
            }
        }
        val exitCode = try { started.waitFor() } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            -1
        }
        process = null
        if (!stopping) {
            val detail = recent.joinToString(" | ").takeLast(MAX_ERROR_CHARS)
            val message = if (detail.isBlank()) {
                "cloudflared exited with code $exitCode"
            } else {
                "cloudflared exited with code $exitCode: $detail"
            }
            Log.e(LOG_TAG, redact(message))
            setState(false, null, message)
            stopSelf()
        }
    }

    private fun resolveEdgeAddresses(): List<String> {
        val hosts = listOf(
            "region1.v2.argotunnel.com",
            "fed-region2.v2.argotunnel.com"
        )
        val addresses = linkedSetOf<String>()
        hosts.forEach { host ->
            try {
                InetAddress.getAllByName(host)
                    .filterIsInstance<Inet4Address>()
                    .forEach { addresses.add("${it.hostAddress}:7844") }
            } catch (error: Throwable) {
                Log.w(LOG_TAG, "Android DNS could not resolve $host: ${error.message}")
            }
        }
        check(addresses.isNotEmpty()) {
            "Android DNS could not resolve Cloudflare Tunnel edge addresses"
        }
        return addresses.take(8)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "DroidIME Named Tunnel", NotificationManager.IMPORTANCE_LOW)
            )
        }
    }

    private fun notification(text: String): Notification {
        val builder = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, CHANNEL_ID)
            else Notification.Builder(this)
        return builder.setContentTitle("DroidIME")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .build()
    }

    private fun setState(running: Boolean, url: String?, error: String?) {
        isRunning = running
        isStarting = !running && url != null && error == null
        tunnelUrl = url
        lastError = error
        status = error ?: if (running) "Named Tunnel connected" else if (url != null) "Connecting to Named Tunnel…" else "Stopped"
        getSharedPreferences("droid_mcp", MODE_PRIVATE).edit()
            .putBoolean("tunnel_running", running)
            .putString("tunnel_url", url)
            .putString("tunnel_error", error)
            .putString("tunnel_status", status)
            .apply()
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(status))
    }

    override fun onDestroy() {
        stopping = true
        try { process?.destroy() } catch (_: Throwable) {}
        try { if (process?.isAlive == true) process?.destroyForcibly() } catch (_: Throwable) {}
        process = null
        isRunning = false
        isStarting = false
        tunnelUrl = null
        val savedError = lastError
        status = savedError ?: "Stopped"
        getSharedPreferences("droid_mcp", MODE_PRIVATE).edit()
            .putBoolean("tunnel_running", false)
            .putString("tunnel_url", null)
            .putString("tunnel_error", savedError)
            .putString("tunnel_status", status)
            .apply()
        executor.shutdownNow()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val CHANNEL_ID = "droidime_cloudflare"
        private const val LOG_TAG = "DroidIMECloudflared"

        private fun redact(value: String): String =
            value.replace(Regex("(?i)(--token\\s+|token[=: ]+)\\S+")) { it.groupValues[1] + "<REDACTED>" }
                .replace(Regex("eyJ[A-Za-z0-9+/=_-]{24,}"), "<REDACTED_TOKEN>")
        private const val NOTIFICATION_ID = 8788
        private const val MAX_OUTPUT_LINES = 12
        private const val MAX_ERROR_CHARS = 700

        @Volatile var isRunning = false
        @Volatile var isStarting = false
        @Volatile var tunnelUrl: String? = null
        @Volatile var lastError: String? = null
        @Volatile var status: String = "Stopped"
    }
}
