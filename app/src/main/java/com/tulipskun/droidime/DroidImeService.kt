package com.tulipskun.droidime

import android.graphics.Color
import android.graphics.Typeface
import android.inputmethodservice.InputMethodService
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.LinearLayout

class DroidImeService : InputMethodService() {
    private enum class Language(val code: String, val label: String) {
        THAI("th", "ไทย"),
        ENGLISH("en", "EN")
    }

    private var language = Language.THAI
    private var shifted = false
    private var symbols = false
    private lateinit var keyboard: LinearLayout

    private val thaiRows = listOf(
        listOf("ๆ", "ไ", "ำ", "พ", "ะ", "ั", "ี", "ร", "น", "ย", "บ", "ล", "ฃ"),
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
            setBackgroundColor(Color.rgb(224, 228, 236))
        }
        loadSettings()
        renderKeyboard()
        return keyboard
    }

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        shifted = ((info?.inputType ?: 0) and android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES) != 0
        loadSettings()
        if (::keyboard.isInitialized) renderKeyboard()
    }

    private fun loadSettings() {
        val enabled = enabledLanguages()
        val saved = preferences.getString("current_language", language.code)
        language = enabled.firstOrNull { it.code == saved } ?: enabled.first()
        val height = preferences.getInt("keyboard_height_dp", 280)
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
        val height = preferences.getInt("keyboard_height_dp", 280)
        val rowHeight = dp(((height - 31) / 4).coerceAtLeast(42))
        val horizontalPadding = dp(4)
        keyboard.setPadding(horizontalPadding, dp(8), horizontalPadding, dp(8))

        val rows = when {
            symbols -> symbolRows
            language == Language.THAI -> thaiRows
            else -> englishRows
        }
        rows.forEachIndexed { index, row ->
            val line = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
            }
            if (index == 2) addKey(line, if (shifted) "⇧" else "↑", 1.2f) {
                shifted = !shifted
                renderKeyboard()
            }
            row.forEach { key ->
                val label = if (language == Language.ENGLISH && shifted && !symbols) key.uppercase() else key
                addKey(line, label, 1f) { commitKey(label) }
            }
            if (index == 2) addKey(line, "⌫", 1.3f) { deleteOne() }
            keyboard.addView(line, LinearLayout.LayoutParams(-1, rowHeight).apply {
                bottomMargin = dp(5)
            })
        }

        val bottom = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        addKey(bottom, if (symbols) "ABC" else "?123", 1.2f) {
            symbols = !symbols
            renderKeyboard()
        }
        addKey(bottom, "🌐", 1f) { switchLanguage() }
        addKey(bottom, ",", 0.65f) { currentInputConnection?.commitText(",", 1) }
        addKey(bottom, "space", 3.6f) { currentInputConnection?.commitText(" ", 1) }
        addKey(bottom, ".", 0.65f) { currentInputConnection?.commitText(".", 1) }
        addKey(bottom, "↵", 1f) { sendEnter() }
        keyboard.addView(bottom, LinearLayout.LayoutParams(-1, rowHeight))
    }

    private fun switchLanguage() {
        val enabled = enabledLanguages()
        if (enabled.size < 2) return
        val next = enabled[(enabled.indexOf(language) + 1) % enabled.size]
        language = next
        preferences.edit().putString("current_language", language.code).apply()
        symbols = false
        shifted = false
        renderKeyboard()
    }

    private fun addKey(row: LinearLayout, label: String, weight: Float, action: () -> Unit) {
        val button = Button(this).apply {
            text = label
            textSize = if (label.length > 3) 12f else 18f
            setTextColor(Color.rgb(32, 39, 53))
            typeface = Typeface.DEFAULT
            isAllCaps = false
            minWidth = 0
            minimumWidth = 0
            minHeight = 0
            minimumHeight = 0
            setPadding(0, 0, 0, 0)
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(if (label == "↵" || label == "space") Color.rgb(174, 190, 222) else Color.WHITE)
                cornerRadius = dp(8).toFloat()
            }
            contentDescription = when (label) {
                "🌐" -> "Switch language"
                "⌫" -> "Delete"
                "↵" -> "Enter"
                "space" -> "Space"
                else -> label
            }
            setOnClickListener { action() }
        }
        row.addView(button, LinearLayout.LayoutParams(0, -1, weight).apply {
            marginStart = dp(2)
            marginEnd = dp(2)
        })
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
