package com.tulipskun.droidime

import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.inputmethodservice.InputMethodService
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.LinearLayout
import android.widget.TextView

class DroidImeService : InputMethodService() {
    private enum class Language(val code: String) {
        THAI("th"),
        ENGLISH("en")
    }

    private var language = Language.THAI
    private var shifted = false
    private var symbols = false
    private var navigationInset = 0
    private lateinit var keyboard: LinearLayout
    private val deleteHandler = Handler(Looper.getMainLooper())
    private var deleteRepeat: Runnable? = null

    private val thaiRows = listOf(
        listOf("ๅ", "/", "-", "ภ", "ถ", "ุ", "ึ", "ค", "ต", "จ", "ข", "ช"),
        listOf("ๆ", "ไ", "ำ", "พ", "ะ", "ั", "ี", "ร", "น", "ย", "บ", "ล"),
        listOf("ฟ", "ห", "ก", "ด", "เ", "้", "่", "า", "ส", "ว", "ง"),
        listOf("ผ", "ป", "แ", "อ", "ิ", "ื", "ท", "ม", "ใ", "ฝ")
    )
    private val thaiShift = mapOf(
        "ๅ" to "+", "/" to "๑", "-" to "๒", "ภ" to "๓", "ถ" to "๔",
        "ุ" to "ู", "ึ" to "฿", "ค" to "๕", "ต" to "๖", "จ" to "๗", "ข" to "๘", "ช" to "๙",
        "ๆ" to "๐", "ไ" to "\"", "ำ" to "ฎ", "พ" to "ฑ", "ะ" to "ธ",
        "ั" to "ํ", "ี" to "๊", "ร" to "ณ", "น" to "ฯ", "ย" to "ญ",
        "บ" to "ฐ", "ล" to ",", "ฟ" to "ฤ", "ห" to "ฆ", "ก" to "ฏ",
        "ด" to "โ", "เ" to "ฌ", "้" to "็", "่" to "๋", "า" to "ษ",
        "ส" to "ศ", "ว" to "ซ", "ง" to ".", "ผ" to "(", "ป" to ")",
        "แ" to "ฉ", "อ" to "ฮ", "ิ" to "ฺ", "ื" to "์", "ท" to "?",
        "ม" to "ฒ", "ใ" to "ฬ", "ฝ" to "ฦ"
    )
    private val englishRows = listOf(
        listOf("q", "w", "e", "r", "t", "y", "u", "i", "o", "p"),
        listOf("a", "s", "d", "f", "g", "h", "j", "k", "l"),
        listOf("z", "x", "c", "v", "b", "n", "m")
    )
    private val symbolRows = listOf(
        listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "0"),
        listOf("@", "#", "$", "%", "&", "-", "+", "(", ")"),
        listOf("*", "\"", "'", ":", ";", "!", "?", "/")
    )

    private val preferences by lazy {
        getSharedPreferences("keyboard_settings", MODE_PRIVATE)
    }

    override fun onCreateInputView(): View {
        keyboard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(Color.rgb(218, 222, 230))
            setOnApplyWindowInsetsListener { view, insets ->
                navigationInset = insets.systemWindowInsetBottom
                view.setPadding(dp(3), dp(8), dp(3), dp(8) + navigationInset)
                insets
            }
        }
        getWindow().window?.let { imeWindow ->
            if (Build.VERSION.SDK_INT >= 29) {
                imeWindow.isNavigationBarContrastEnforced = false
            }
            imeWindow.navigationBarColor = Color.rgb(218, 222, 230)
        }
        loadSettings()
        renderKeyboard()
        keyboard.requestApplyInsets()
        return keyboard
    }

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        val flags = info?.inputType ?: InputType.TYPE_CLASS_TEXT
        symbols = false
        if (::keyboard.isInitialized) {
            loadSettings()
            shifted = language == Language.ENGLISH && ((flags and (
                InputType.TYPE_TEXT_FLAG_CAP_SENTENCES or
                    InputType.TYPE_TEXT_FLAG_CAP_WORDS or
                    InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS
                )) != 0)
            renderKeyboard()
        } else {
            shifted = false
        }
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        stopDeleteRepeat()
        super.onFinishInputView(finishingInput)
    }

    override fun onDestroy() {
        stopDeleteRepeat()
        super.onDestroy()
    }

    private fun loadSettings() {
        val enabled = enabledLanguages()
        val saved = preferences.getString("current_language", language.code)
        language = enabled.firstOrNull { it.code == saved } ?: enabled.first()
        val height = preferences.getInt("keyboard_height_dp", 280).coerceIn(220, 360)
        val params = keyboard.layoutParams ?: ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            dp(height)
        )
        params.height = dp(height)
        keyboard.layoutParams = params
    }

    private fun enabledLanguages(): List<Language> {
        val thai = preferences.getBoolean("language_th", true)
        val english = preferences.getBoolean("language_en", true)
        return buildList {
            if (thai) add(Language.THAI)
            if (english) add(Language.ENGLISH)
        }.ifEmpty { listOf(Language.THAI) }
    }

    private fun renderKeyboard() {
        keyboard.removeAllViews()
        val rows = when {
            symbols -> symbolRows
            language == Language.THAI -> thaiRows
            else -> englishRows
        }
        val height = preferences.getInt("keyboard_height_dp", 280).coerceIn(220, 360)
        val insetDp = (navigationInset / resources.displayMetrics.density).toInt()
        val rowCount = rows.size + 1
        val rowHeight = dp(((height - insetDp - 16 - rowCount * 4) / rowCount).coerceAtLeast(36))
        keyboard.setPadding(dp(3), dp(8), dp(3), dp(8) + navigationInset)

        rows.forEachIndexed { index, row ->
            val line = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
            }
            if (index == rows.lastIndex) {
                addKey(line, if (shifted) "⇧" else "↑", 1.25f, KeyStyle.SPECIAL) {
                    shifted = !shifted
                    renderKeyboard()
                }
            }
            row.forEach { key ->
                val label = when {
                    symbols -> key
                    language == Language.THAI && shifted -> thaiShift[key] ?: key
                    language == Language.ENGLISH && shifted -> key.uppercase()
                    else -> key
                }
                addKey(line, label, 1f, KeyStyle.NORMAL) { commitKey(label) }
            }
            if (index == rows.lastIndex) {
                addKey(line, "⌫", 1.25f, KeyStyle.SPECIAL) { deleteOne() }
            }
            keyboard.addView(line, LinearLayout.LayoutParams(-1, rowHeight).apply {
                bottomMargin = dp(4)
            })
        }

        val bottom = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        addKey(bottom, if (symbols) "ABC" else "?123", 1.25f, KeyStyle.SPECIAL) {
            symbols = !symbols
            renderKeyboard()
        }
        addKey(bottom, "🌐", 1f, KeyStyle.SPECIAL) { switchLanguage() }
        addKey(bottom, ",", 0.7f, KeyStyle.NORMAL) { currentInputConnection?.commitText(",", 1) }
        addKey(bottom, "space", 3.8f, KeyStyle.SPACE) { currentInputConnection?.commitText(" ", 1) }
        addKey(bottom, ".", 0.7f, KeyStyle.NORMAL) { currentInputConnection?.commitText(".", 1) }
        addKey(bottom, "↵", 1.15f, KeyStyle.ENTER) { sendEnter() }
        keyboard.addView(bottom, LinearLayout.LayoutParams(-1, rowHeight))
    }

    private enum class KeyStyle { NORMAL, SPECIAL, SPACE, ENTER }

    private fun addKey(
        row: LinearLayout,
        label: String,
        weight: Float,
        style: KeyStyle,
        action: () -> Unit
    ) {
        val key = TextView(this).apply {
            text = label
            gravity = Gravity.CENTER
            textSize = when {
                label == "space" -> 12f
                label.length > 1 -> 14f
                else -> 20f
            }
            setTextColor(Color.rgb(34, 39, 49))
            typeface = Typeface.DEFAULT
            isSingleLine = true
            background = GradientDrawable().apply {
                setColor(when (style) {
                    KeyStyle.NORMAL -> Color.WHITE
                    KeyStyle.SPECIAL -> Color.rgb(194, 202, 216)
                    KeyStyle.SPACE -> Color.WHITE
                    KeyStyle.ENTER -> Color.rgb(91, 119, 180)
                })
                cornerRadius = dp(7).toFloat()
            }
            if (style == KeyStyle.ENTER) setTextColor(Color.WHITE)
            contentDescription = when (label) {
                "🌐" -> "Switch language"
                "⌫" -> "Delete"
                "↵" -> "Enter"
                "space" -> "Space"
                "⇧", "↑" -> "Shift"
                else -> label
            }
            setOnClickListener { action() }
            if (label == "⌫") {
                setOnLongClickListener {
                    stopDeleteRepeat()
                    deleteOne()
                    val repeat = object : Runnable {
                        override fun run() {
                            deleteOne()
                            deleteHandler.postDelayed(this, 55)
                        }
                    }
                    deleteRepeat = repeat
                    deleteHandler.postDelayed(repeat, 280)
                    true
                }
                setOnTouchListener { _, event ->
                    if (event.action == MotionEvent.ACTION_UP ||
                        event.action == MotionEvent.ACTION_CANCEL) {
                        stopDeleteRepeat()
                    }
                    false
                }
            }
        }
        row.addView(key, LinearLayout.LayoutParams(0, -1, weight).apply {
            marginStart = dp(2)
            marginEnd = dp(2)
        })
    }

    private fun stopDeleteRepeat() {
        deleteRepeat?.let { deleteHandler.removeCallbacks(it) }
        deleteRepeat = null
    }

    private fun switchLanguage() {
        val enabled = enabledLanguages()
        if (enabled.size < 2) return
        language = enabled[(enabled.indexOf(language) + 1) % enabled.size]
        preferences.edit().putString("current_language", language.code).apply()
        symbols = false
        shifted = false
        renderKeyboard()
    }

    private fun commitKey(value: String) {
        currentInputConnection?.commitText(value, 1)
        if (shifted && !symbols) {
            shifted = false
            renderKeyboard()
        }
    }

    private fun deleteOne() {
        val connection = currentInputConnection ?: return
        connection.deleteSurroundingText(1, 0)
    }

    private fun sendEnter() {
        val action = currentInputEditorInfo?.imeOptions?.and(EditorInfo.IME_MASK_ACTION)
        if (action != null && action != EditorInfo.IME_ACTION_NONE && action != EditorInfo.IME_ACTION_UNSPECIFIED) {
            currentInputConnection?.performEditorAction(action)
        } else {
            currentInputConnection?.commitText("\n", 1)
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
