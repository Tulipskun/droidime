package com.tulipskun.droidime

import android.app.Notification
import android.app.NotificationChannel
import android.os.Build
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.IBinder

class McpService : Service() {
    private var server: McpServer? = null
    private var overlay: PointerOverlay? = null

    override fun onCreate() {
        super.onCreate()

        val manager = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "DroidIME MCP", NotificationManager.IMPORTANCE_LOW)
        )

        val notificationBuilder = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, CHANNEL_ID)
            else Notification.Builder(this)
        val notification = notificationBuilder
            .setContentTitle("DroidIME")
            .setContentText("MCP endpoint /" + DeviceEndpoint.getOrCreateName(this))
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .build()

        startForeground(NOTIFICATION_ID, notification)

        val preferences = getSharedPreferences("droid_mcp", MODE_PRIVATE)
        // The switch in MainActivity writes to keyboard_settings, not droid_mcp.
        val uiPreferences = getSharedPreferences("keyboard_settings", MODE_PRIVATE)
        try {
            val deviceName = DeviceEndpoint.getOrCreateName(this)

            // The overlay is best-effort: without the draw-over-apps permission the
            // pointer is simply invisible and every tool still works.
            val pointerOverlay = PointerOverlay(this).also {
                it.setEnabled(uiPreferences.getBoolean("pointer_overlay", true))
                overlay = it
            }
            // Create the overlay window now, not lazily on the first action.
            pointerOverlay.attach()

            server = McpServer(
                CloudflareTunnelConfig.LOCAL_PORT,
                ShizukuBridge(this, preferences, pointerOverlay),
                DeviceEndpoint.path(deviceName)
            )
            server!!.start()
            isRunning = true
        } catch (_: Throwable) {
            isRunning = false
            server = null
            stopSelf()
        }
    }

    override fun onDestroy() {
        isRunning = false
        server?.stop()
        server = null
        overlay?.setEnabled(false)
        overlay = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val CHANNEL_ID = "droid_mcp"
        private const val NOTIFICATION_ID = 8787

        @Volatile
        var isRunning: Boolean = false
    }
}
