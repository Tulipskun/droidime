package com.tulipskun.droidime

import android.app.Activity
import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

class MainActivity : Activity() {
    private val executor = Executors.newSingleThreadExecutor()
    private lateinit var status: TextView
    private lateinit var updateButton: Button
    private var downloadId = -1L
    private var receiverRegistered = false

    private val downloadReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1L) != downloadId) return
            val manager = getSystemService(DOWNLOAD_SERVICE) as DownloadManager
            val uri = manager.getUriForDownloadedFile(downloadId)
            if (uri == null) {
                status.text = getString(R.string.update_download_failed)
                return
            }
            status.text = getString(R.string.update_installing)
            val installIntent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(installIntent)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(28, 32, 28, 32)
            setBackgroundColor(0xFFF4F5F8.toInt())
        }

        root.addView(TextView(this).apply {
            text = getString(R.string.setup_title)
            textSize = 30f
            setTextColor(0xFF182033.toInt())
            gravity = Gravity.CENTER
        })

        root.addView(TextView(this).apply {
            text = getString(R.string.setup_message)
            textSize = 16f
            setTextColor(0xFF596174.toInt())
            gravity = Gravity.CENTER
            setPadding(0, 16, 0, 24)
        })

        root.addView(Button(this).apply {
            text = getString(R.string.open_settings)
            setOnClickListener { startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS)) }
        })

        root.addView(Button(this).apply {
            text = getString(R.string.select_keyboard)
            setOnClickListener {
                val imm = getSystemService(INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
                imm.showInputMethodPicker()
            }
        })

        status = TextView(this).apply {
            text = getString(R.string.update_current, BuildConfig.VERSION_NAME)
            textSize = 14f
            setTextColor(0xFF596174.toInt())
            gravity = Gravity.CENTER
            setPadding(0, 24, 0, 8)
        }
        root.addView(status)

        updateButton = Button(this).apply {
            text = getString(R.string.check_updates)
            setOnClickListener { checkForUpdate() }
        }
        root.addView(updateButton)

        setContentView(root)
    }

    private fun checkForUpdate() {
        updateButton.isEnabled = false
        status.text = getString(R.string.update_checking)

        executor.execute {
            try {
                val connection = (URL("https://api.github.com/repos/Tulipskun/droidime/releases/latest").openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = 15000
                    readTimeout = 15000
                    setRequestProperty("Accept", "application/vnd.github+json")
                    setRequestProperty("User-Agent", "DroidIME-Android")
                }
                val response = connection.inputStream.bufferedReader().use { it.readText() }
                connection.disconnect()

                val release = JSONObject(response)
                val notes = release.optString("body")
                val version = Regex("versionCode=(\\d+)").find(notes)?.groupValues?.get(1)?.toIntOrNull()
                    ?: throw IllegalStateException(getString(R.string.update_version_missing))
                val assets = release.getJSONArray("assets")
                var apkUrl: String? = null
                for (i in 0 until assets.length()) {
                    val asset = assets.getJSONObject(i)
                    if (asset.optString("name") == "DroidIME.apk") {
                        apkUrl = asset.optString("browser_download_url")
                        break
                    }
                }
                val downloadUrl = apkUrl ?: throw IllegalStateException(getString(R.string.update_apk_missing))

                runOnUiThread {
                    updateButton.isEnabled = true
                    if (version <= BuildConfig.VERSION_CODE) {
                        status.text = getString(R.string.update_latest)
                    } else {
                        status.text = getString(R.string.update_available, version)
                        installUpdate(downloadUrl)
                    }
                }
            } catch (error: Exception) {
                runOnUiThread {
                    updateButton.isEnabled = true
                    status.text = error.message ?: getString(R.string.update_check_failed)
                }
            }
        }
    }

    private fun installUpdate(downloadUrl: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !packageManager.canRequestPackageInstalls()) {
            status.text = getString(R.string.update_allow_installs)
            val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
                data = Uri.parse("package:$packageName")
            }
            startActivity(intent)
            return
        }

        updateButton.isEnabled = false
        status.text = getString(R.string.update_downloading)

        val request = DownloadManager.Request(Uri.parse(downloadUrl)).apply {
            setTitle(getString(R.string.app_name))
            setDescription(getString(R.string.update_downloading))
            setMimeType("application/vnd.android.package-archive")
            setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            setDestinationInExternalFilesDir(this@MainActivity, Environment.DIRECTORY_DOWNLOADS, "DroidIME.apk")
        }

        val manager = getSystemService(DOWNLOAD_SERVICE) as DownloadManager
        downloadId = manager.enqueue(request)
        val filter = IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE)
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(downloadReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            registerReceiver(downloadReceiver, filter)
        }
        receiverRegistered = true
    }

    override fun onDestroy() {
        if (receiverRegistered) unregisterReceiver(downloadReceiver)
        executor.shutdownNow()
        super.onDestroy()
    }
}
