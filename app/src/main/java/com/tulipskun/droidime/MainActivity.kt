package com.tulipskun.droidime

import android.app.Activity
import android.app.AlertDialog
import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

class MainActivity : Activity() {
    private val executor = Executors.newSingleThreadExecutor()
    private lateinit var status: TextView
    private lateinit var setupStatus: TextView
    private lateinit var updateButton: TextView
    private lateinit var downloadProgress: ProgressBar
    private val progressHandler = Handler(Looper.getMainLooper())
    private var progressRunnable: Runnable? = null
    private var downloadId = -1L
    private var receiverRegistered = false
    private var pendingUrl: String? = null
    private var retries = 0
    private var lastBytes = -1L
    private var lastProgressAt = 0L

    private val preferences by lazy {
        getSharedPreferences("keyboard_settings", MODE_PRIVATE)
    }

    // The settings screen follows the same light/dark/system choice as the keyboard.
    private val dark by lazy {
        when (preferences.getString("theme", "system")) {
            "dark" -> true
            "light" -> false
            else -> (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
                Configuration.UI_MODE_NIGHT_YES
        }
    }
    private val bgColor get() = if (dark) Color.rgb(20, 21, 24) else Color.rgb(244, 246, 250)
    private val cardColor get() = if (dark) Color.rgb(36, 38, 43) else Color.WHITE
    private val headingColor get() = if (dark) Color.rgb(238, 240, 245) else Color.rgb(27, 35, 52)
    private val sectionColor get() = if (dark) Color.rgb(232, 234, 240) else Color.rgb(31, 40, 58)
    private val mutedColor get() = if (dark) Color.rgb(160, 167, 180) else Color.rgb(105, 113, 128)
    private val bodyColor get() = if (dark) Color.rgb(185, 191, 203) else Color.rgb(82, 91, 108)
    private val inputColor get() = if (dark) Color.rgb(52, 54, 60) else Color.rgb(245, 247, 251)
    private val okColor get() = if (dark) Color.rgb(98, 201, 150) else Color.rgb(36, 125, 84)

    private val downloadReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1L) == downloadId) {
                refreshDownloadStatus()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        setTheme(if (dark) R.style.AppThemeDark else R.style.AppTheme)
        super.onCreate(savedInstanceState)

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(18), dp(20), dp(24))
        }

        content.addView(TextView(this).apply {
            text = "DroidIME"
            textSize = 30f
            setTextColor(headingColor)
            typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
        })
        content.addView(TextView(this).apply {
            text = "Keyboard settings"
            textSize = 14f
            setTextColor(mutedColor)
            setPadding(0, dp(2), 0, dp(18))
        })

        val setupCard = card()
        setupCard.addView(sectionTitle("Keyboard setup"))
        setupStatus = TextView(this).apply {
            textSize = 14f
            setTextColor(bodyColor)
            setPadding(0, dp(4), 0, dp(12))
        }
        setupCard.addView(setupStatus)
        setupCard.addView(actionButton("Enable DroidIME") {
            startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS))
        })
        setupCard.addView(actionButton("Select DroidIME") {
            val manager = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
            manager.showInputMethodPicker()
        })
        content.addView(setupCard, sectionParams())

        val testCard = card()
        testCard.addView(sectionTitle("Try the keyboard"))
        testCard.addView(TextView(this).apply {
            text = "Tap the field and type to verify the active keyboard."
            textSize = 13f
            setTextColor(mutedColor)
            setPadding(0, 0, 0, dp(10))
        })
        testCard.addView(EditText(this).apply {
            hint = "Type something…"
            textSize = 16f
            minLines = 2
            maxLines = 4
            gravity = Gravity.TOP
            inputType = android.text.InputType.TYPE_CLASS_TEXT or
                android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES or
                android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = rounded(inputColor, dp(10))
        })
        content.addView(testCard, sectionParams())

        val languageCard = card()
        languageCard.addView(sectionTitle("Languages"))
        val thaiCheck = CheckBox(this).apply {
            text = "ไทย"
            textSize = 15f
            isChecked = preferences.getBoolean("language_th", true)
        }
        val englishCheck = CheckBox(this).apply {
            text = "English"
            textSize = 15f
            isChecked = preferences.getBoolean("language_en", true)
        }
        thaiCheck.setOnCheckedChangeListener { _, checked ->
            if (!checked && !englishCheck.isChecked) {
                thaiCheck.isChecked = true
            } else {
                preferences.edit().putBoolean("language_th", checked).apply()
            }
        }
        englishCheck.setOnCheckedChangeListener { _, checked ->
            if (!checked && !thaiCheck.isChecked) {
                englishCheck.isChecked = true
            } else {
                preferences.edit().putBoolean("language_en", checked).apply()
            }
        }
        languageCard.addView(thaiCheck)
        languageCard.addView(englishCheck)
        content.addView(languageCard, sectionParams())

        val sizeCard = card()
        val currentHeight = preferences.getInt("keyboard_height_dp", 250).coerceIn(220, 360)
        val heightLabel = sectionTitle("Keyboard height · ${currentHeight} dp")
        sizeCard.addView(heightLabel)
        sizeCard.addView(SeekBar(this).apply {
            max = 140
            progress = currentHeight - 220
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    val height = 220 + progress
                    heightLabel.text = "Keyboard height · ${height} dp"
                    preferences.edit().putInt("keyboard_height_dp", height).apply()
                }
                override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
                override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
            })
        })
        content.addView(sizeCard, sectionParams())

        val themeCard = card()
        themeCard.addView(sectionTitle("Keyboard theme"))
        val currentTheme = preferences.getString("theme", "system")
        val themeGroup = RadioGroup(this).apply { orientation = LinearLayout.VERTICAL }
        listOf("system" to "Follow system", "light" to "Light", "dark" to "Dark").forEach { (value, name) ->
            themeGroup.addView(RadioButton(this).apply {
                id = View.generateViewId()
                text = name
                textSize = 15f
                tag = value
                isChecked = value == currentTheme
            })
        }
        themeGroup.setOnCheckedChangeListener { group, checkedId ->
            val value = group.findViewById<RadioButton>(checkedId)?.tag as? String
            if (value != null && value != preferences.getString("theme", "system")) {
                preferences.edit().putString("theme", value).apply()
                recreate()
            }
        }
        themeCard.addView(themeGroup)
        content.addView(themeCard, sectionParams())

        val feedbackCard = card()
        feedbackCard.addView(sectionTitle("Feedback"))
        feedbackCard.addView(prefSwitch("Vibrate on key press", "haptics", true))
        val currentStrength = preferences.getInt("haptic_ms", 15).coerceIn(5, 50)
        val strengthLabel = TextView(this).apply {
            text = "Vibration strength · $currentStrength ms"
            textSize = 13f
            setTextColor(mutedColor)
            setPadding(0, dp(6), 0, 0)
        }
        feedbackCard.addView(strengthLabel)
        feedbackCard.addView(SeekBar(this).apply {
            max = 45
            progress = currentStrength - 5
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    val ms = 5 + progress
                    strengthLabel.text = "Vibration strength · $ms ms"
                    preferences.edit().putInt("haptic_ms", ms).apply()
                }
                override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
                override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
            })
        })
        feedbackCard.addView(prefSwitch("Sound on key press", "key_sound", false))
        content.addView(feedbackCard, sectionParams())

        val typingCard = card()
        typingCard.addView(sectionTitle("Typing"))
        typingCard.addView(prefSwitch("Word suggestions (Thai and English)", "suggestions", true))
        typingCard.addView(prefSwitch("Glide typing (Thai and English)", "glide_typing", true))
        typingCard.addView(prefSwitch("Auto-capitalize (English)", "auto_caps", true))
        typingCard.addView(prefSwitch("Double space inserts a period (English)", "double_space_period", true))
        typingCard.addView(prefSwitch("Show key hints (long-press to type)", "key_hints", true))
        typingCard.addView(prefSwitch("Number row on English keyboard", "number_row_en", true))
        typingCard.addView(prefSwitch("Number row on Thai keyboard", "number_row_th", false))
        content.addView(typingCard, sectionParams())

        val updateCard = card()
        updateCard.addView(sectionTitle("App update"))
        status = TextView(this).apply {
            text = getString(R.string.update_current, BuildConfig.VERSION_NAME)
            textSize = 13f
            setTextColor(mutedColor)
            setPadding(0, dp(2), 0, dp(10))
        }
        updateCard.addView(status)
        downloadProgress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            progress = 0
            visibility = View.GONE
        }
        updateCard.addView(downloadProgress, LinearLayout.LayoutParams(-1, dp(6)).apply {
            bottomMargin = dp(10)
        })
        updateButton = actionButton("Check for updates") { checkForUpdate() }
        updateCard.addView(updateButton)
        content.addView(updateCard, sectionParams())

        val logCard = card()
        logCard.addView(sectionTitle("Diagnostic log"))
        logCard.addView(TextView(this).apply {
            text = "Crash reports are stored inside the app and remain available after a restart."
            textSize = 13f
            setTextColor(mutedColor)
            setPadding(0, 0, 0, dp(8))
        })
        logCard.addView(actionButton("View / copy crash log") { showCrashLog() })
        content.addView(logCard, sectionParams())

        val scroll = ScrollView(this).apply {
            isFillViewport = true
            clipToPadding = false
            addView(content)
            setBackgroundColor(bgColor)
            setOnApplyWindowInsetsListener { view, insets ->
                view.setPadding(
                    dp(20),
                    insets.systemWindowInsetTop + dp(14),
                    dp(20),
                    insets.systemWindowInsetBottom + dp(18)
                )
                insets
            }
        }
        setContentView(scroll)
        scroll.requestApplyInsets()
    }

    override fun onResume() {
        super.onResume()
        if (::setupStatus.isInitialized) refreshSetupStatus()
    }

    private fun showCrashLog() {
        val file = java.io.File(filesDir, "crash.log")
        val log = if (file.exists()) file.readText() else "No crash log has been recorded."
        AlertDialog.Builder(this)
            .setTitle("Crash log")
            .setMessage(log.takeLast(14000))
            .setPositiveButton("Copy full log") { _, _ ->
                val clipboard = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("DroidIME crash log", log))
                android.widget.Toast.makeText(this, "Crash log copied", android.widget.Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Close", null)
            .show()
    }

    private fun refreshSetupStatus() {
        val component = ComponentName(this, DroidImeService::class.java)
        val manager = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
        // Stored IME ids use the short "pkg/.Class" form; compare as ComponentName so both
        // the short and fully-qualified spellings match.
        val enabled = manager.enabledInputMethodList.any {
            ComponentName.unflattenFromString(it.id) == component
        }
        val selected = ComponentName.unflattenFromString(
            Settings.Secure.getString(contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)
        ) == component
        setupStatus.text = when {
            selected -> "DroidIME is enabled and selected."
            enabled -> "DroidIME is enabled. Select it as the current keyboard."
            else -> "DroidIME is not enabled yet."
        }
        setupStatus.setTextColor(if (selected) okColor else bodyColor)
    }

    private fun card() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(16), dp(15), dp(16), dp(16))
        background = rounded(cardColor, dp(16))
        elevation = dp(1).toFloat()
    }

    private fun sectionTitle(value: String) = TextView(this).apply {
        text = value
        textSize = 17f
        typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
        setTextColor(sectionColor)
        setPadding(0, 0, 0, dp(9))
    }

    private fun actionButton(value: String, action: () -> Unit) = TextView(this).apply {
        text = value
        textSize = 14f
        gravity = Gravity.CENTER
        setTextColor(Color.WHITE)
        setPadding(dp(14), dp(12), dp(14), dp(12))
        background = rounded(Color.rgb(49, 91, 190), dp(10))
        isClickable = true
        isFocusable = true
        setOnClickListener { action() }
        layoutParams = LinearLayout.LayoutParams(-1, -2).apply {
            topMargin = dp(7)
        }
    }

    private fun prefSwitch(label: String, key: String, default: Boolean) = Switch(this).apply {
        text = label
        textSize = 15f
        setPadding(0, dp(6), 0, dp(6))
        isChecked = preferences.getBoolean(key, default)
        setOnCheckedChangeListener { _, checked ->
            preferences.edit().putBoolean(key, checked).apply()
        }
    }

    private fun sectionParams() = LinearLayout.LayoutParams(-1, -2).apply {
        bottomMargin = dp(13)
    }

    private fun rounded(color: Int, radius: Int) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = radius.toFloat()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

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
                val version = Regex("versionCode\\s*[=:]?\\s*(\\d+)").find(notes)?.groupValues?.get(1)?.toIntOrNull()
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
                        retries = 0
                        pendingUrl = downloadUrl
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
            updateButton.isEnabled = true
            status.text = getString(R.string.update_allow_installs)
            startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
                data = Uri.parse("package:$packageName")
            })
            return
        }

        try {
            val manager = getSystemService(DOWNLOAD_SERVICE) as DownloadManager
            // A leftover download or file from an earlier attempt can leave the new one stuck at
            // 0 B (pending) or failing with "file already exists", so clear both first.
            val previousId = preferences.getLong("update_download_id", -1L)
            if (previousId != -1L) manager.remove(previousId)
            if (downloadId != -1L) manager.remove(downloadId)
            getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)?.let {
                java.io.File(it, "DroidIME-update.apk").delete()
            }
            if (receiverRegistered) {
                unregisterReceiver(downloadReceiver)
                receiverRegistered = false
            }

            val filter = IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE)
            if (Build.VERSION.SDK_INT >= 33) {
                registerReceiver(downloadReceiver, filter, Context.RECEIVER_EXPORTED)
            } else {
                @Suppress("DEPRECATION")
                registerReceiver(downloadReceiver, filter)
            }
            receiverRegistered = true

            val request = DownloadManager.Request(Uri.parse(downloadUrl)).apply {
                setTitle(getString(R.string.app_name))
                setDescription("Downloading DroidIME update")
                setMimeType("application/vnd.android.package-archive")
                setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                setDestinationInExternalFilesDir(this@MainActivity, Environment.DIRECTORY_DOWNLOADS, "DroidIME-update.apk")
                setAllowedOverMetered(true)
                setAllowedOverRoaming(true)
            }

            downloadProgress.visibility = View.VISIBLE
            downloadProgress.isIndeterminate = true
            downloadProgress.progress = 0
            updateButton.isEnabled = false
            status.text = "Starting download…"
            downloadId = manager.enqueue(request)
            preferences.edit().putLong("update_download_id", downloadId).apply()
            lastBytes = -1L
            lastProgressAt = SystemClock.uptimeMillis()
            startDownloadPolling()
        } catch (error: Exception) {
            stopDownloadPolling()
            if (receiverRegistered) {
                unregisterReceiver(downloadReceiver)
                receiverRegistered = false
            }
            downloadProgress.visibility = View.GONE
            updateButton.isEnabled = true
            status.text = "Could not start download: ${error.message ?: "unknown error"}"
        }
    }

    private fun startDownloadPolling() {
        stopDownloadPolling()
        progressRunnable = object : Runnable {
            override fun run() {
                refreshDownloadStatus()
                if (downloadId != -1L && progressRunnable === this) {
                    progressHandler.postDelayed(this, 350)
                }
            }
        }
        progressHandler.post(progressRunnable!!)
    }

    private fun stopDownloadPolling() {
        progressRunnable?.let { progressHandler.removeCallbacks(it) }
        progressRunnable = null
    }

    private fun refreshDownloadStatus() {
        if (downloadId == -1L || isFinishing || isDestroyed) return
        val manager = getSystemService(DOWNLOAD_SERVICE) as DownloadManager
        val cursor = manager.query(DownloadManager.Query().setFilterById(downloadId)) ?: return
        cursor.use {
            if (!it.moveToFirst()) return
            val state = it.getInt(it.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
            val downloaded = it.getLong(it.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR))
            val total = it.getLong(it.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES))
            when (state) {
                DownloadManager.STATUS_PENDING, DownloadManager.STATUS_RUNNING, DownloadManager.STATUS_PAUSED -> {
                    val now = SystemClock.uptimeMillis()
                    if (downloaded != lastBytes) {
                        lastBytes = downloaded
                        lastProgressAt = now
                    } else if (state != DownloadManager.STATUS_PAUSED && now - lastProgressAt > STALL_MS) {
                        restartStalledDownload(manager)
                        return
                    }
                    downloadProgress.isIndeterminate = total <= 0L
                    if (total > 0L) {
                        val percent = ((downloaded * 100L) / total).toInt().coerceIn(0, 100)
                        downloadProgress.progress = percent
                        status.text = "Downloading update: $percent% · ${formatBytes(downloaded)} / ${formatBytes(total)}"
                    } else {
                        status.text = "Downloading update · ${formatBytes(downloaded)}"
                    }
                    if (state == DownloadManager.STATUS_PAUSED) {
                        val reason = it.getInt(it.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON))
                        status.text = "Download paused: ${downloadReason(reason)}"
                    }
                }
                DownloadManager.STATUS_SUCCESSFUL -> {
                    val completedId = downloadId
                    stopDownloadPolling()
                    downloadId = -1L
                    downloadProgress.isIndeterminate = false
                    downloadProgress.progress = 100
                    downloadProgress.visibility = View.GONE
                    preferences.edit().remove("update_download_id").apply()
                    updateButton.isEnabled = true
                    if (receiverRegistered) {
                        unregisterReceiver(downloadReceiver)
                        receiverRegistered = false
                    }
                    openDownloadedInstaller(manager, completedId)
                }
                DownloadManager.STATUS_FAILED -> {
                    val reason = it.getInt(it.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON))
                    stopDownloadPolling()
                    downloadId = -1L
                    downloadProgress.visibility = View.GONE
                    updateButton.isEnabled = true
                    status.text = "Download failed: ${downloadReason(reason)} (code $reason)"
                    preferences.edit().remove("update_download_id").apply()
                    if (receiverRegistered) {
                        unregisterReceiver(downloadReceiver)
                        receiverRegistered = false
                    }
                }
            }
        }
    }

    // No bytes for STALL_MS (stuck pending, dead connection): drop the download and start over a
    // couple of times before giving up, so a hung request doesn't need a manual retry.
    private fun restartStalledDownload(manager: DownloadManager) {
        stopDownloadPolling()
        manager.remove(downloadId)
        downloadId = -1L
        preferences.edit().remove("update_download_id").apply()
        val url = pendingUrl
        if (url != null && retries < MAX_RETRIES) {
            retries++
            status.text = "Download stalled, retrying ($retries/$MAX_RETRIES)…"
            installUpdate(url)
        } else {
            if (receiverRegistered) {
                unregisterReceiver(downloadReceiver)
                receiverRegistered = false
            }
            downloadProgress.visibility = View.GONE
            updateButton.isEnabled = true
            status.text = "Download stalled. Check your connection and try again."
        }
    }

    private fun openDownloadedInstaller(manager: DownloadManager, completedId: Long) {
        val uri = manager.getUriForDownloadedFile(completedId)
        if (uri == null) {
            updateButton.isEnabled = true
            status.text = "Download finished, but the APK file could not be opened."
            return
        }
        try {
            status.text = "Download complete. Opening installer…"
            val installIntent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(installIntent)
        } catch (error: Exception) {
            updateButton.isEnabled = true
            status.text = "Could not open installer: ${error.message ?: "unknown error"}"
        }
    }

    private fun downloadReason(reason: Int): String = when (reason) {
        DownloadManager.ERROR_CANNOT_RESUME -> "cannot resume"
        DownloadManager.ERROR_DEVICE_NOT_FOUND -> "storage unavailable"
        DownloadManager.ERROR_FILE_ALREADY_EXISTS -> "file already exists"
        DownloadManager.ERROR_FILE_ERROR -> "file error"
        DownloadManager.ERROR_HTTP_DATA_ERROR -> "network data error"
        DownloadManager.ERROR_INSUFFICIENT_SPACE -> "not enough storage"
        DownloadManager.ERROR_TOO_MANY_REDIRECTS -> "too many redirects"
        DownloadManager.ERROR_UNHANDLED_HTTP_CODE -> "HTTP error"
        DownloadManager.ERROR_UNKNOWN -> "unknown download error"
        DownloadManager.PAUSED_QUEUED_FOR_WIFI -> "waiting for Wi-Fi"
        DownloadManager.PAUSED_WAITING_FOR_NETWORK -> "waiting for network"
        DownloadManager.PAUSED_WAITING_TO_RETRY -> "waiting to retry"
        DownloadManager.PAUSED_UNKNOWN -> "paused"
        else -> "reason $reason"
    }

    private fun formatBytes(bytes: Long): String {
        if (bytes < 1024L) return "$bytes B"
        if (bytes < 1024L * 1024L) return String.format("%.1f KB", bytes / 1024.0)
        return String.format("%.1f MB", bytes / (1024.0 * 1024.0))
    }

    companion object {
        private const val STALL_MS = 25_000L
        private const val MAX_RETRIES = 2
    }

    override fun onDestroy() {
        stopDownloadPolling()
        if (receiverRegistered) unregisterReceiver(downloadReceiver)
        executor.shutdownNow()
        super.onDestroy()
    }
}
