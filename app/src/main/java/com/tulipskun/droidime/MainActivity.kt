package com.tulipskun.droidime

import android.app.Activity
import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
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
    private var downloadId = -1L
    private var receiverRegistered = false

    private val preferences by lazy {
        getSharedPreferences("keyboard_settings", MODE_PRIVATE)
    }

    private val downloadReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1L) != downloadId) return
            val manager = getSystemService(DOWNLOAD_SERVICE) as DownloadManager
            val uri = manager.getUriForDownloadedFile(downloadId)
            if (uri == null) {
                status.text = getString(R.string.update_download_failed)
                updateButton.isEnabled = true
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

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(18), dp(20), dp(24))
        }

        content.addView(TextView(this).apply {
            text = "DroidIME"
            textSize = 30f
            setTextColor(Color.rgb(27, 35, 52))
            typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
        })
        content.addView(TextView(this).apply {
            text = "Keyboard settings"
            textSize = 14f
            setTextColor(Color.rgb(105, 113, 128))
            setPadding(0, dp(2), 0, dp(18))
        })

        val setupCard = card()
        setupCard.addView(sectionTitle("Keyboard setup"))
        setupStatus = TextView(this).apply {
            textSize = 14f
            setTextColor(Color.rgb(82, 91, 108))
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
            setTextColor(Color.rgb(105, 113, 128))
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
            background = rounded(Color.rgb(245, 247, 251), dp(10))
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
        val currentHeight = preferences.getInt("keyboard_height_dp", 280).coerceIn(220, 360)
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

        val updateCard = card()
        updateCard.addView(sectionTitle("App update"))
        status = TextView(this).apply {
            text = getString(R.string.update_current, BuildConfig.VERSION_NAME)
            textSize = 13f
            setTextColor(Color.rgb(105, 113, 128))
            setPadding(0, dp(2), 0, dp(10))
        }
        updateCard.addView(status)
        updateButton = actionButton("Check for updates") { checkForUpdate() }
        updateCard.addView(updateButton)
        content.addView(updateCard, sectionParams())

        val scroll = ScrollView(this).apply {
            isFillViewport = true
            clipToPadding = false
            addView(content)
            setBackgroundColor(Color.rgb(244, 246, 250))
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

    private fun refreshSetupStatus() {
        val component = ComponentName(this, DroidImeService::class.java).flattenToString()
        val manager = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
        val enabled = manager.enabledInputMethodList.any { it.id == component }
        val selected = Settings.Secure.getString(contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD) == component
        setupStatus.text = when {
            selected -> "DroidIME is enabled and selected."
            enabled -> "DroidIME is enabled. Select it as the current keyboard."
            else -> "DroidIME is not enabled yet."
        }
        setupStatus.setTextColor(if (selected) Color.rgb(36, 125, 84) else Color.rgb(82, 91, 108))
    }

    private fun card() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(16), dp(15), dp(16), dp(16))
        background = rounded(Color.WHITE, dp(16))
        elevation = dp(1).toFloat()
    }

    private fun sectionTitle(value: String) = TextView(this).apply {
        text = value
        textSize = 17f
        typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
        setTextColor(Color.rgb(31, 40, 58))
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
