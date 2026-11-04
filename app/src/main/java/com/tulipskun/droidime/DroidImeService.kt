package com.tulipskun.droidime

import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.inputmethodservice.InputMethodService
import android.text.InputType
import android.view.Gravity
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
    private lateinit var keyboard: LinearLayout

    private val thaiRows = listOf(
        listOf("ๅ", "ภ", "ถ", "ุ", "ึ", "ค", "ต", "จ", "ข", "ช"),
        listOf("ๆ", "ไ", "ำ", "พ", "ะ", "ั", "ี", "ร", "น", "ย", "บ", "ล"),
        listOf("ฟ", "ห", "ก", "ด", "เ", "้", "่", "า", "ส", "ว", "ง"),
        listOf("ผ", "ป", "แ", "อ", "ิ", "ื", "ท", "ม", "ใ", "ฝ")
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
        }
        loadSettings()
        renderKeyboard()
        return keyboard
    }

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        val flags = info?.inputType ?: InputType.TYPE_CLASS_TEXT
        shifted = flags and (InputType.TYPE_TEXT_FLAG_CAP_SENTENCES or
            InputType.TYPE_TEXT_FLAG_CAP_WORDS or InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS) != 0
        symbols = false
        loadSettings()
        if (::keyboard.isInitialized) renderKeyboard()
    }

    private fun loadSettings() {
        val enabled = enabledLanguages()
        val saved = preferences.getString("current_language", language.code)
        language = enabled.firstOrNull { it.code == saved } ?: enabled.first()
        val height = preferences.getInt("keyboard_height_dp", 280).coerceIn(220, 360)
        keyboard.layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            dp(height)
        )
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
        val rowCount = rows.size + 1
        val rowHeight = dp(((height - 16 - rowCount * 4) / rowCount).coerceAtLeast(36))
        keyboard.setPadding(dp(3), dp(8), dp(3), dp(8))

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
                val label = if (language == Language.ENGLISH && shifted && !symbols) key.uppercase() else key
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
                    KeyStyle.SPACE -> Color.rgb(255, 255, 255)
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
                else -> label
            }
            setOnClickListener { action() }
        }
        row.addView(key, LinearLayout.LayoutParams(0, -1, weight).apply {
            marginStart = dp(2)
            marginEnd = dp(2)
        })
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
        if (shifted && language == Language.ENGLISH) {
            shifted = false
            renderKeyboard()
        }
    }

    private fun deleteOne() {
        val connection = currentInputConnection ?: return
        if (!connection.deleteSurroundingText(1, 0)) {
            connection.sendKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, android.view.KeyEvent.KEYCODE_DEL))
            connection.sendKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_UP, android.view.KeyEvent.KEYCODE_DEL))
        }
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
