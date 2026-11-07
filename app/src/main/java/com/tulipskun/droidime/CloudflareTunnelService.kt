package com.tulipskun.droidime

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
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
                val token = TunnelTokenStore.read(this)
                    ?: throw IllegalStateException("Cloudflare Tunnel token is not configured")
                val deviceName = DeviceEndpoint.getOrCreateName(this)
                require(DeviceEndpoint.isValid(deviceName)) { "Enter a valid device name" }
                startTunnel(token, deviceName)
            } catch (error: Throwable) {
                if (!stopping) setState(false, null, error.message ?: "Cloudflare Named Tunnel failed")
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
        val command = listOf(
            binary.absolutePath,
            "tunnel",
            "--no-autoupdate",
            "--protocol", "http2",
            "--metrics", "127.0.0.1:${CloudflareTunnelConfig.METRICS_PORT}",
            "run",
            "--token", token
        )
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
                    recent.addLast(line.take(500))
                    while (recent.size > MAX_OUTPUT_LINES) recent.removeFirst()
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
            setState(false, null, if (detail.isBlank()) {
                "cloudflared exited with code $exitCode"
            } else {
                "cloudflared exited with code $exitCode: $detail"
            })
            stopSelf()
        }
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
        status = "Stopped"
        getSharedPreferences("droid_mcp", MODE_PRIVATE).edit()
            .putBoolean("tunnel_running", false)
            .putString("tunnel_url", null)
            .putString("tunnel_error", null)
            .putString("tunnel_status", "Stopped")
            .apply()
        executor.shutdownNow()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val CHANNEL_ID = "droidime_cloudflare"
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
