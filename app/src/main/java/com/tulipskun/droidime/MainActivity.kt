package com.tulipskun.droidime

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.content.pm.PackageManager
import android.widget.Toast
import rikka.shizuku.Shizuku
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
import java.nio.charset.StandardCharsets
import java.util.concurrent.Executors

class MainActivity : Activity() {
    private val executor = Executors.newSingleThreadExecutor()
    private lateinit var status: TextView
    private lateinit var setupStatus: TextView
    private lateinit var updateButton: TextView
    private lateinit var downloadProgress: ProgressBar
    @Volatile private var downloading = false
    @Volatile private var destroyed = false
    private lateinit var remoteMcpStatus: TextView
    private lateinit var remoteMcpToggle: TextView
    private lateinit var remoteTunnelToggle: TextView
    private lateinit var remoteTunnelStatus: TextView
    private lateinit var remoteEndpoint: TextView
    private lateinit var tokenSavedStatus: TextView
    private lateinit var webhookField: EditText
    private lateinit var webhookStatus: TextView
    private lateinit var sendWebhookButton: TextView
    private lateinit var copyCurlButton: TextView
    private val remotePrefs by lazy { getSharedPreferences("droid_mcp", MODE_PRIVATE) }
    private val remoteStatusHandler = Handler(Looper.getMainLooper())
    private val remoteStatusPoll = object : Runnable {
        override fun run() {
            if (!destroyed) {
                refreshRemoteStatus()
                remoteStatusHandler.postDelayed(this, 1000)
            }
        }
    }
    private val shizukuPermissionListener = Shizuku.OnRequestPermissionResultListener { requestCode, grantResult ->
        if (requestCode == SHIZUKU_PERMISSION_REQUEST) runOnUiThread {
            Toast.makeText(this, if (grantResult == PackageManager.PERMISSION_GRANTED) "Shizuku authorized" else "Shizuku permission denied", Toast.LENGTH_SHORT).show()
            refreshRemoteStatus()
        }
    }

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

        val remoteCard = card()
        remoteCard.addView(sectionTitle("Remote control · MCP"))
        remoteCard.addView(TextView(this).apply {
            text = "Expose Android control tools at https://${DeviceEndpoint.HOST}/<device-name>. Protect the gateway with Cloudflare Access: the MCP endpoint has no client login."
            textSize = 13f
            setTextColor(mutedColor)
            setPadding(0, 0, 0, dp(9))
        })
        remoteMcpStatus = TextView(this).apply {
            textSize = 13f
            setTextColor(bodyColor)
            setPadding(0, dp(3), 0, dp(5))
        }
        remoteCard.addView(remoteMcpStatus)
        remoteCard.addView(actionButton("Connect / authorize Shizuku") { requestShizuku() })
        remoteMcpToggle = actionButton("Start MCP Server") { toggleMcpServer() }
        remoteCard.addView(remoteMcpToggle)
        val deviceNameField = EditText(this).apply {
            hint = "device-name"
            textSize = 15f
            setSingleLine(true)
            setText(DeviceEndpoint.getOrCreateName(this@MainActivity))
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = rounded(inputColor, dp(10))
        }
        remoteCard.addView(TextView(this).apply {
            text = "Device name"
            textSize = 13f
            setTextColor(mutedColor)
            setPadding(0, dp(12), 0, dp(5))
        })
        remoteCard.addView(deviceNameField, LinearLayout.LayoutParams(-1, -2))
        remoteCard.addView(TextView(this).apply {
            text = "Public endpoint: https://${DeviceEndpoint.HOST}/<device-name>"
            textSize = 12f
            setTextColor(mutedColor)
            setPadding(0, dp(5), 0, dp(2))
        })
        remoteCard.addView(actionButton("Save device name") {
            val deviceName = DeviceEndpoint.normalize(deviceNameField.text.toString())
            if (!DeviceEndpoint.isValid(deviceName)) {
                Toast.makeText(this, "Use 1–32 lowercase letters, numbers or hyphens", Toast.LENGTH_SHORT).show()
            } else {
                val previousName = DeviceEndpoint.getOrCreateName(this)
                if (previousName != deviceName) {
                    stopService(Intent(this, CloudflareTunnelService::class.java))
                    stopService(Intent(this, McpService::class.java))
                }
                remotePrefs.edit().putString("device_name", deviceName).apply()
                deviceNameField.setText(deviceName)
                refreshRemoteStatus()
            }
        })
        val apiTokenField = EditText(this).apply {
            hint = "Paste Cloudflare API token (CF_TOKEN)"
            textSize = 15f
            setSingleLine(true)
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = rounded(inputColor, dp(10))
        }
        remoteCard.addView(TextView(this).apply {
            text = "Cloudflare API token (CF_TOKEN)"
            textSize = 13f
            setTextColor(mutedColor)
            setPadding(0, dp(12), 0, dp(5))
        })
        remoteCard.addView(TextView(this).apply {
            text = "Required permissions: Cloudflare Tunnel Edit/Write, DNS Edit/Write, Workers KV Storage Edit/Write, and Access Apps and Policies Edit/Write. The token is encrypted with Android Keystore."
            textSize = 12f
            setTextColor(mutedColor)
            setPadding(0, 0, 0, dp(7))
        })
        remoteCard.addView(apiTokenField, LinearLayout.LayoutParams(-1, -2))
        tokenSavedStatus = TextView(this).apply {
            textSize = 12f
            setTextColor(mutedColor)
            setPadding(0, dp(5), 0, 0)
        }
        remoteCard.addView(tokenSavedStatus)
        remoteCard.addView(actionButton("Save CF_TOKEN") {
            val value = apiTokenField.text.toString().trim()
            if (value.isBlank()) {
                Toast.makeText(this, "Cloudflare API token is required", Toast.LENGTH_SHORT).show()
            } else {
                try {
                    CloudflareApiTokenStore.save(this, value)
                    apiTokenField.text.clear()
                    refreshRemoteStatus()
                } catch (error: Throwable) {
                    Toast.makeText(this, error.message ?: "Unable to save CF_TOKEN", Toast.LENGTH_LONG).show()
                }
            }
        })
        remoteCard.addView(actionButton("Clear saved CF_TOKEN") {
            AlertDialog.Builder(this)
                .setTitle("Delete Cloudflare API token?")
                .setMessage("Existing Tunnel credentials remain saved. Automatic provisioning will require a new CF_TOKEN.")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Delete") { _, _ ->
                    CloudflareApiTokenStore.clear(this)
                    refreshRemoteStatus()
                }.show()
        })
        remoteCard.addView(actionButton("Provision Named Tunnel") {
            val deviceName = DeviceEndpoint.normalize(deviceNameField.text.toString())
            if (!DeviceEndpoint.isValid(deviceName)) {
                Toast.makeText(this, "Enter a valid device name first", Toast.LENGTH_SHORT).show()
            } else {
                val previousName = DeviceEndpoint.getOrCreateName(this)
                if (previousName != deviceName) {
                    stopService(Intent(this, CloudflareTunnelService::class.java))
                    stopService(Intent(this, McpService::class.java))
                }
                remotePrefs.edit().putString("device_name", deviceName).apply()
                provisionNamedTunnel(deviceName)
            }
        })
        remoteCard.addView(actionButton("Clear saved Tunnel token") {
            AlertDialog.Builder(this)
                .setTitle("Delete saved Tunnel token?")
                .setMessage("The Named Tunnel will stop working until it is provisioned again.")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Delete") { _, _ ->
                    stopService(Intent(this, CloudflareTunnelService::class.java))
                    TunnelTokenStore.clear(this)
                    tokenSavedStatus.text = "Tunnel token cleared"
                    refreshRemoteStatus()
                }.show()
        })
        remoteTunnelStatus = TextView(this).apply {
            textSize = 13f
            setTextColor(bodyColor)
            setPadding(0, dp(12), 0, dp(4))
        }
        remoteCard.addView(remoteTunnelStatus)
        remoteTunnelToggle = actionButton("Start Named Tunnel") { toggleNamedTunnel() }
        remoteCard.addView(remoteTunnelToggle)
        remoteEndpoint = TextView(this).apply {
            textSize = 13f
            setTextColor(mutedColor)
            setPadding(0, dp(7), 0, dp(4))
        }
        remoteCard.addView(remoteEndpoint)
        remoteCard.addView(actionButton("Copy MCP endpoint") {
            val url = remoteEndpoint.text.toString().removePrefix("Endpoint: ").trim()
            if (url.startsWith("https://") || url.startsWith("http://")) {
                getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("DroidIME MCP", url))
                Toast.makeText(this, "MCP endpoint copied", Toast.LENGTH_SHORT).show()
            }
        })
        webhookField = EditText(this).apply {
            hint = "https://example.com/webhook"
            textSize = 15f
            setSingleLine(true)
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_URI
            setText(remotePrefs.getString("webhook_url", ""))
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = rounded(inputColor, dp(10))
            setOnFocusChangeListener { _, hasFocus ->
                if (!hasFocus) {
                    remotePrefs.edit().putString("webhook_url", text.toString().trim()).apply()
                    updateWebhookButtons()
                }
            }
        }
        remoteCard.addView(TextView(this).apply {
            text = "Webhook URL"
            textSize = 13f
            setTextColor(mutedColor)
            setPadding(0, dp(12), 0, dp(5))
        })
        remoteCard.addView(webhookField, LinearLayout.LayoutParams(-1, -2))
        webhookStatus = TextView(this).apply {
            textSize = 12f
            setTextColor(mutedColor)
            setPadding(0, dp(5), 0, dp(2))
        }
        remoteCard.addView(webhookStatus)
        sendWebhookButton = actionButton("Send MCP URL to webhook") { sendMcpUrlToWebhook() }
        remoteCard.addView(sendWebhookButton)
        copyCurlButton = actionButton("Copy webhook cURL command") { copyWebhookCurl() }
        remoteCard.addView(copyCurlButton)
        val autoStart = Switch(this).apply {
            text = "Start MCP and Named Tunnel after reboot"
            textSize = 14f
            isChecked = remotePrefs.getBoolean("auto_start", false)
            setOnCheckedChangeListener { _, checked -> remotePrefs.edit().putBoolean("auto_start", checked).apply() }
        }
        remoteCard.addView(autoStart)
        content.addView(remoteCard, sectionParams())
        Shizuku.addRequestPermissionResultListener(shizukuPermissionListener)
        refreshRemoteStatus()

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
        typingCard.addView(prefSwitch("Clipboard history (tap the clipboard icon)", "clipboard", true))
        content.addView(typingCard, sectionParams())

        val dictionaryCard = card()
        dictionaryCard.addView(sectionTitle("Dictionary"))
        dictionaryCard.addView(TextView(this).apply {
            text = "Search words in the built-in Thai and English dictionaries and your personal dictionary."
            textSize = 13f
            setTextColor(mutedColor)
            setPadding(0, 0, 0, dp(8))
        })
        val dictionaryQuery = EditText(this).apply {
            hint = "Enter a word to search"
            textSize = 15f
            maxLines = 1
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = rounded(inputColor, dp(10))
            imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH
        }
        dictionaryCard.addView(dictionaryQuery, LinearLayout.LayoutParams(-1, -2).apply {
            bottomMargin = dp(7)
        })
        val dictionaryResult = TextView(this).apply {
            text = "Search to check whether a word is stored."
            textSize = 13f
            setTextColor(bodyColor)
            setPadding(0, dp(8), 0, 0)
        }
        val runDictionarySearch = {
            searchDictionary(dictionaryQuery.text.toString(), dictionaryResult)
        }
        dictionaryCard.addView(actionButton("Search dictionary", runDictionarySearch))
        dictionaryQuery.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH) {
                runDictionarySearch()
                true
            } else false
        }
        dictionaryCard.addView(dictionaryResult)
        content.addView(dictionaryCard, sectionParams())

        val learningCard = card()
        learningCard.addView(sectionTitle("Touch coordinate learning"))
        val eventCount = preferences.getString("touch_history", "").orEmpty().lineSequence().count { it.isNotBlank() }
        learningCard.addView(TextView(this).apply {
            text = "Recorded touch and glide coordinates: $eventCount events (maximum 3,000).\nCoordinates are normalized to the keyboard area (0.0–1.0), with local pixel positions for key taps. Corrections are marked when Backspace is used shortly after a touch. No typed message content is stored."
            textSize = 13f
            setTextColor(bodyColor)
            setPadding(0, dp(2), 0, dp(10))
        })
        learningCard.addView(actionButton("Export touch data (JSON)") { exportTouchHistory() })
        learningCard.addView(actionButton("Clear touch data") {
            AlertDialog.Builder(this)
                .setTitle("Clear touch data?")
                .setMessage("All recorded key positions and glide paths will be deleted.")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Clear") { _, _ ->
                    preferences.edit().remove("touch_history").apply()
                    recreate()
                }
                .show()
        })
        content.addView(learningCard, sectionParams())

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
        remoteStatusHandler.removeCallbacks(remoteStatusPoll)
        remoteStatusHandler.post(remoteStatusPoll)
    }

    override fun onPause() {
        remoteStatusHandler.removeCallbacks(remoteStatusPoll)
        super.onPause()
    }

    private fun requestShizuku() {
        try {
            if (!Shizuku.pingBinder()) {
                remoteMcpStatus.text = "Shizuku is not running. Start Shizuku first."
                return
            }
            if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
                remoteMcpStatus.text = "Shizuku authorized"
            } else {
                Shizuku.requestPermission(SHIZUKU_PERMISSION_REQUEST)
                remoteMcpStatus.text = "Waiting for Shizuku authorization…"
            }
        } catch (error: Throwable) {
            remoteMcpStatus.text = error.message ?: "Shizuku unavailable"
        }
    }

    private fun toggleMcpServer() {
        if (McpService.isRunning) {
            stopService(Intent(this, CloudflareTunnelService::class.java))
            stopService(Intent(this, McpService::class.java))
            refreshRemoteStatus()
            return
        }
        try {
            if (!Shizuku.pingBinder() || Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
                requestShizuku()
                return
            }
            val intent = Intent(this, McpService::class.java)
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(intent) else startService(intent)
            remoteMcpStatus.text = "Starting MCP Server…"
        } catch (error: Throwable) {
            remoteMcpStatus.text = error.message ?: "Unable to start MCP Server"
        }
    }

    private fun toggleNamedTunnel() {
        if (CloudflareTunnelService.isRunning || CloudflareTunnelService.isStarting || remotePrefs.getBoolean("tunnel_running", false)) {
            stopService(Intent(this, CloudflareTunnelService::class.java))
            refreshRemoteStatus()
            return
        }
        try {
            val deviceName = DeviceEndpoint.getOrCreateName(this)
            if (!DeviceEndpoint.isValid(deviceName)) {
                Toast.makeText(this, "Enter a valid device name first", Toast.LENGTH_LONG).show()
                return
            }
            val savedTunnelToken = TunnelTokenStore.read(this)
            val provisionedName = remotePrefs.getString("tunnel_token_device_name", null)
            val needsProvisioning = savedTunnelToken.isNullOrBlank() ||
                (provisionedName != null && provisionedName != deviceName)
            if (needsProvisioning) {
                if (CloudflareApiTokenStore.read(this).isNullOrBlank()) {
                    Toast.makeText(this, "Save CF_TOKEN to provision the Named Tunnel automatically", Toast.LENGTH_LONG).show()
                    return
                }
                provisionNamedTunnel(deviceName)
                return
            }
            startNamedTunnelService()
        } catch (error: Throwable) {
            remoteTunnelStatus.text = error.message ?: "Unable to start Named Tunnel"
        }
    }

    private fun provisionNamedTunnel(deviceName: String) {
        val apiToken = try {
            CloudflareApiTokenStore.read(this)
        } catch (error: Throwable) {
            remoteTunnelStatus.text = error.message ?: "Unable to read CF_TOKEN"
            return
        }
        if (apiToken.isNullOrBlank()) {
            Toast.makeText(this, "Save CF_TOKEN first", Toast.LENGTH_LONG).show()
            return
        }
        remoteTunnelToggle.isEnabled = false
        remoteTunnelStatus.text = "Provisioning Cloudflare Named Tunnel…"
        remotePrefs.edit().remove("tunnel_error").putString("tunnel_status", "Provisioning Cloudflare Named Tunnel…").apply()
        executor.execute {
            try {
                val result = CloudflareProvisioner.provision(this, apiToken, deviceName)
                TunnelTokenStore.save(this, result.tunnelToken)
                remotePrefs.edit()
                    .putString("device_name", deviceName)
                    .putString("tunnel_token_device_name", deviceName)
                    .putString("tunnel_status", "Cloudflare resources configured")
                    .remove("tunnel_error")
                    .apply()
                runOnUiThread {
                    remoteTunnelToggle.isEnabled = true
                    remoteTunnelStatus.text = "Cloudflare resources configured: " + result.hostname
                    refreshRemoteStatus()
                    startNamedTunnelService()
                }
            } catch (error: Throwable) {
                val message = error.message ?: "Cloudflare provisioning failed"
                remotePrefs.edit().putString("tunnel_error", message).putString("tunnel_status", message).apply()
                runOnUiThread {
                    remoteTunnelToggle.isEnabled = true
                    remoteTunnelStatus.text = message
                    refreshRemoteStatus()
                }
            }
        }
    }

    private fun startNamedTunnelService() {
        try {
            if (!McpService.isRunning) {
                if (!Shizuku.pingBinder() || Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
                    remoteTunnelStatus.text = "Cloudflare configured. Authorize Shizuku to start MCP."
                    requestShizuku()
                    return
                }
                val mcp = Intent(this, McpService::class.java)
                if (Build.VERSION.SDK_INT >= 26) startForegroundService(mcp) else startService(mcp)
            }
            val tunnel = Intent(this, CloudflareTunnelService::class.java)
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(tunnel) else startService(tunnel)
            remoteTunnelStatus.text = "Starting Named Tunnel…"
        } catch (error: Throwable) {
            remoteTunnelStatus.text = error.message ?: "Unable to start Named Tunnel"
        }
    }

    private fun refreshRemoteStatus() {
        if (!::remoteMcpStatus.isInitialized) return
        val prefs = remotePrefs
        val mcpRunning = McpService.isRunning
        remoteMcpStatus.text = "MCP Server: " + if (mcpRunning) "Running · 0.0.0.0:8787" else "Stopped"
        val tunnelRunning = CloudflareTunnelService.isRunning
        val savedStatus = prefs.getString("tunnel_status", null)
        val savedError = prefs.getString("tunnel_error", null)
        remoteTunnelStatus.text = "Named Tunnel: " + when {
            tunnelRunning -> CloudflareTunnelService.status
            savedError != null -> savedError
            savedStatus != null && savedStatus != "Stopped" -> savedStatus
            else -> "Stopped"
        }
        val deviceName = DeviceEndpoint.getOrCreateName(this)
        val url = CloudflareTunnelService.tunnelUrl ?: DeviceEndpoint.endpoint(deviceName)
        remoteEndpoint.text = "Endpoint: $url"
        val apiTokenSaved = try { !CloudflareApiTokenStore.read(this).isNullOrBlank() } catch (_: Throwable) { false }
        val tunnelTokenSaved = try { !TunnelTokenStore.read(this).isNullOrBlank() } catch (_: Throwable) { false }
        tokenSavedStatus.text = when {
            apiTokenSaved && tunnelTokenSaved -> "CF_TOKEN and Tunnel token saved encrypted on this device"
            apiTokenSaved -> "CF_TOKEN saved encrypted on this device"
            tunnelTokenSaved -> "Tunnel token saved encrypted · CF_TOKEN not configured"
            else -> "No Cloudflare credentials saved"
        }
        remoteMcpToggle.text = if (mcpRunning) "Stop MCP Server" else "Start MCP Server"
        remoteTunnelToggle.text = if (tunnelRunning || CloudflareTunnelService.isStarting) "Stop Named Tunnel" else "Start Named Tunnel"
        if (::webhookField.isInitialized) updateWebhookButtons()
    }

    private fun updateWebhookButtons() {
        val configured = webhookField.text.toString().trim().isNotBlank()
        val endpointAvailable = (CloudflareTunnelService.tunnelUrl
            ?: remotePrefs.getString("tunnel_url", "")).orEmpty().isNotBlank()
        sendWebhookButton.isEnabled = configured && endpointAvailable
        copyCurlButton.isEnabled = configured && endpointAvailable
    }

    private fun currentMcpEndpoint(): String =
        (CloudflareTunnelService.tunnelUrl ?: DeviceEndpoint.endpoint(DeviceEndpoint.getOrCreateName(this))).trim()

    private fun sendMcpUrlToWebhook() {
        val webhook = webhookField.text.toString().trim()
        remotePrefs.edit().putString("webhook_url", webhook).apply()
        val endpoint = currentMcpEndpoint()
        if (!webhook.startsWith("https://", ignoreCase = true)) {
            webhookStatus.text = "Webhook must use HTTPS"
            updateWebhookButtons()
            return
        }
        if (!CloudflareTunnelService.isRunning) {
            webhookStatus.text = "Start Named Tunnel first"
            updateWebhookButtons()
            return
        }
        sendWebhookButton.isEnabled = false
        copyCurlButton.isEnabled = false
        webhookStatus.text = "Sending MCP URL…"
        val payload = JSONObject().put("url", endpoint).toString()
        executor.execute {
            var connection: HttpURLConnection? = null
            try {
                connection = URL(webhook).openConnection() as HttpURLConnection
                connection.requestMethod = "POST"
                connection.connectTimeout = 10000
                connection.readTimeout = 15000
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                connection.setRequestProperty("Accept", "application/json, text/plain, */*")
                connection.outputStream.use { it.write(payload.toByteArray(StandardCharsets.UTF_8)) }
                val code = connection.responseCode
                runOnUiThread {
                    webhookStatus.text = if (code in 200..299) "Webhook sent (HTTP $code)" else "Webhook failed (HTTP $code)"
                    updateWebhookButtons()
                }
            } catch (error: Throwable) {
                runOnUiThread {
                    webhookStatus.text = "Webhook failed: ${error.message ?: "network error"}"
                    updateWebhookButtons()
                }
            } finally {
                connection?.disconnect()
            }
        }
    }

    private fun copyWebhookCurl() {
        val webhook = webhookField.text.toString().trim()
        val endpoint = currentMcpEndpoint()
        if (!webhook.startsWith("https://", ignoreCase = true) || endpoint.isBlank()) {
            webhookStatus.text = "Webhook and MCP URL are required"
            return
        }
        val payload = JSONObject().put("url", endpoint).toString()
        fun quote(value: String) = "'" + value.replace("'", "'\\''") + "'"
        val command = "curl -X POST ${quote(webhook)} -H ${quote("Content-Type: application/json")} --data ${quote(payload)}"
        getSystemService(ClipboardManager::class.java)
            .setPrimaryClip(ClipData.newPlainText("DroidIME cURL", command))
        webhookStatus.text = "cURL command copied"
    }

    private fun searchDictionary(rawQuery: String, output: TextView) {
        val query = rawQuery.trim()
        if (query.isEmpty()) {
            output.text = "Enter a word to search."
            return
        }
        output.text = "Searching dictionary…"
        executor.execute {
            try {
                val matches = ArrayList<String>()
                var thaiCount = 0
                var englishCount = 0
                var personalCount = 0
                var thaiExact = false
                var englishExact = false
                var personalExact = false

                fun scanAsset(name: String, isThai: Boolean) {
                    assets.open(name).bufferedReader(Charsets.UTF_8).useLines { lines ->
                        lines.forEach { line ->
                            val tab = line.indexOf(9.toChar())
                            if (tab <= 0) return@forEach
                            val word = line.substring(0, tab)
                            val normalized = if (isThai) word else word.lowercase()
                            val needle = if (isThai) query else query.lowercase()
                            if (normalized == needle) {
                                if (isThai) thaiExact = true else englishExact = true
                            }
                            if (normalized.contains(needle)) {
                                if (isThai) thaiCount++ else englishCount++
                                if (matches.size < 60) matches.add("$word  ·  ${if (isThai) "Thai" else "English"}")
                            }
                        }
                    }
                }

                scanAsset("words_th.txt", true)
                scanAsset("words_en.txt", false)

                val personal = preferences.getString("user_dict", "").orEmpty()
                for (line in personal.lineSequence()) {
                    val tab = line.indexOf(9.toChar())
                    if (tab <= 0) continue
                    val word = line.substring(0, tab)
                    if (word.contains(query, ignoreCase = true)) {
                        personalCount++
                        if (word.equals(query, ignoreCase = true)) personalExact = true
                        if (matches.size < 60) matches.add("$word  ·  Personal")
                    }
                }

                val summary = buildString {
                    append("Exact match: ")
                    append(if (thaiExact || englishExact || personalExact) "FOUND" else "NOT FOUND")
                    append("\nThai: $thaiCount  ·  English: $englishCount  ·  Personal: $personalCount")
                    if (matches.isNotEmpty()) {
                        append("\n\nMatches")
                        matches.forEach { append("\n• ").append(it) }
                        val total = thaiCount + englishCount + personalCount
                        if (total > matches.size) append("\n…and ${total - matches.size} more")
                    } else {
                        append("\n\nNo matching words in the dictionaries.")
                    }
                }
                runOnUiThread {
                    if (!destroyed) output.text = summary
                }
            } catch (error: Exception) {
                runOnUiThread {
                    if (!destroyed) output.text = "Dictionary search failed: ${error.message ?: "unknown error"}"
                }
            }
        }
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
        if (downloading) return
        downloading = true
        downloadProgress.visibility = View.VISIBLE
        downloadProgress.isIndeterminate = true
        updateButton.isEnabled = false
        status.text = "Connecting…"

        // The system DownloadManager can sit at "0 B" forever on some phones (data saver, battery
        // optimisation, a wedged download service), so the app downloads the APK itself: real
        // timeouts, real progress, and a few automatic retries.
        executor.execute {
            val dir = java.io.File(cacheDir, UpdateFileProvider.DIR).apply { mkdirs() }
            dir.listFiles()?.forEach { it.delete() }
            val target = java.io.File(dir, UpdateFileProvider.FILE)
            val part = java.io.File(dir, UpdateFileProvider.FILE + ".part")
            var failure = "unknown error"
            var done = false
            for (attempt in 1..MAX_ATTEMPTS) {
                if (destroyed) return@execute
                try {
                    downloadTo(downloadUrl, part, attempt)
                    done = true
                    break
                } catch (error: Exception) {
                    failure = error.message ?: error.javaClass.simpleName
                    if (attempt < MAX_ATTEMPTS) {
                        ui { status.text = "Download failed ($failure), retrying ${attempt + 1}/$MAX_ATTEMPTS…" }
                        Thread.sleep(1500)
                    }
                }
            }
            if (!done) {
                part.delete()
                finishDownload("Download failed: $failure")
                return@execute
            }
            target.delete()
            if (!part.renameTo(target)) {
                finishDownload("Download failed: could not save the file")
                return@execute
            }
            // Refuse anything that is not a newer build of this app before offering to install it.
            @Suppress("DEPRECATION")
            val info = packageManager.getPackageArchiveInfo(target.path, 0)
            @Suppress("DEPRECATION")
            val code = info?.versionCode ?: -1
            if (info == null || info.packageName != packageName || code <= BuildConfig.VERSION_CODE) {
                target.delete()
                finishDownload("Downloaded file is not a newer DroidIME build")
                return@execute
            }
            ui {
                downloading = false
                downloadProgress.visibility = View.GONE
                updateButton.isEnabled = true
                openInstaller()
            }
        }
    }

    private fun ui(block: () -> Unit) {
        if (destroyed) return
        runOnUiThread { if (!destroyed && !isFinishing) block() }
    }

    private fun finishDownload(message: String) = ui {
        downloading = false
        downloadProgress.visibility = View.GONE
        updateButton.isEnabled = true
        status.text = message
    }

    private fun downloadTo(url: String, out: java.io.File, attempt: Int) {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15000
            readTimeout = 20000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "DroidIME-Android")
            setRequestProperty("Accept", "application/octet-stream")
            setRequestProperty("Accept-Encoding", "identity")
        }
        try {
            val code = connection.responseCode
            if (code != 200) throw java.io.IOException("HTTP $code")
            val total = connection.contentLengthLong
            ui {
                downloadProgress.isIndeterminate = total <= 0L
                status.text = if (attempt > 1) "Downloading update (attempt $attempt)…" else "Downloading update…"
            }
            var downloaded = 0L
            var lastUi = 0L
            connection.inputStream.use { input ->
                out.outputStream().use { output ->
                    val buffer = ByteArray(32 * 1024)
                    while (true) {
                        if (destroyed) throw java.io.IOException("cancelled")
                        val n = input.read(buffer)
                        if (n < 0) break
                        output.write(buffer, 0, n)
                        downloaded += n
                        val now = System.currentTimeMillis()
                        if (now - lastUi > 200) {
                            lastUi = now
                            val soFar = downloaded
                            ui {
                                if (total > 0L) {
                                    val percent = ((soFar * 100L) / total).toInt().coerceIn(0, 100)
                                    downloadProgress.progress = percent
                                    status.text = "Downloading update: $percent% · ${formatBytes(soFar)} / ${formatBytes(total)}"
                                } else {
                                    status.text = "Downloading update · ${formatBytes(soFar)}"
                                }
                            }
                        }
                    }
                }
            }
            if (total > 0L && downloaded != total) {
                throw java.io.IOException("incomplete download (${formatBytes(downloaded)} of ${formatBytes(total)})")
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun openInstaller() {
        val uri = Uri.parse("content://$packageName.updates/${UpdateFileProvider.FILE}")
        try {
            status.text = "Download complete. Opening installer…"
            val installIntent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(installIntent)
        } catch (error: Exception) {
            status.text = "Could not open installer: ${error.message ?: "unknown error"}"
        }
    }

    private fun formatBytes(bytes: Long): String {
        if (bytes < 1024L) return "$bytes B"
        if (bytes < 1024L * 1024L) return String.format("%.1f KB", bytes / 1024.0)
        return String.format("%.1f MB", bytes / (1024.0 * 1024.0))
    }

    companion object {
        private const val MAX_ATTEMPTS = 3
        private const val SHIZUKU_PERMISSION_REQUEST = 2001
    }

    private fun exportTouchHistory() {
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "application/json"
            putExtra(Intent.EXTRA_TITLE, "droidime-touch-history.json")
        }
        startActivityForResult(intent, 4801)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != 4801 || resultCode != RESULT_OK || data?.data == null) return
        try {
            val events = org.json.JSONArray()
            preferences.getString("touch_history", "").orEmpty().lineSequence()
                .filter { it.isNotBlank() }
                .forEach { events.put(org.json.JSONObject(it)) }
            val export = org.json.JSONObject()
                .put("format", "droidime-touch-history")
                .put("version", 1)
                .put("exported_at", System.currentTimeMillis())
                .put("coordinate_system", "normalized_keyboard_xy_0_to_1")
                .put("events", events)
            contentResolver.openOutputStream(data.data!!)?.bufferedWriter()?.use { it.write(export.toString(2)) }
            status.text = "Touch data exported: ${events.length()} events"
        } catch (error: Exception) {
            status.text = "Export failed: ${error.message ?: "unknown error"}"
        }
    }

    override fun onDestroy() {
        destroyed = true
        remoteStatusHandler.removeCallbacksAndMessages(null)
        Shizuku.removeRequestPermissionResultListener(shizukuPermissionListener)
        executor.shutdownNow()
        super.onDestroy()
    }
}
