package com.tulipskun.droidime

import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.inputmethodservice.InputMethodService
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.InputType
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView

class DroidImeService : InputMethodService() {
    private enum class Language(val code: String) {
        THAI("th"),
        ENGLISH("en")
    }

    private enum class FieldKind { TEXT, EMAIL, URL, PASSWORD, NUMBER, PHONE }

    private var language = Language.THAI
    private var fieldKind = FieldKind.TEXT
    private var shifted = false
    private var shiftLocked = false
    private var lastShiftTap = 0L
    private var symbols = false
    private var symbolPage = 0
    private var numberPad = false
    private var navigationInset = 0
    private lateinit var keyboard: LinearLayout
    private val deleteHandler = Handler(Looper.getMainLooper())
    private var deleteRepeat: Runnable? = null

    private val thaiRows = listOf(
        listOf("ๅ", "/", "-", "ภ", "ถ", "ุ", "ึ", "ค", "ต", "จ", "ข", "ช"),
        listOf("ๆ", "ไ", "ำ", "พ", "ะ", "ั", "ี", "ร", "น", "ย", "บ", "ล"),
        listOf("ฟ", "ห", "ก", "ด", "เ", "้", "่", "า", "ส", "ว", "ง", "ฃ"),
        listOf("ผ", "ป", "แ", "อ", "ิ", "ื", "ท", "ม", "ใ", "ฝ")
    )
    private val thaiShift = mapOf(
        "ๅ" to "+", "/" to "๑", "-" to "๒", "ภ" to "๓", "ถ" to "๔",
        "ุ" to "ู", "ึ" to "฿", "ค" to "๕", "ต" to "๖", "จ" to "๗", "ข" to "๘", "ช" to "๙",
        "ๆ" to "๐", "ไ" to "\"", "ำ" to "ฎ", "พ" to "ฑ", "ะ" to "ธ",
        "ั" to "ํ", "ี" to "๊", "ร" to "ณ", "น" to "ฯ", "ย" to "ญ",
        "บ" to "ฐ", "ล" to ",", "ฟ" to "ฤ", "ห" to "ฆ", "ก" to "ฏ",
        "ด" to "โ", "เ" to "ฌ", "้" to "็", "่" to "๋", "า" to "ษ",
        "ส" to "ศ", "ว" to "ซ", "ง" to ".", "ฃ" to "ฅ", "ผ" to "(", "ป" to ")",
        "แ" to "ฉ", "อ" to "ฮ", "ิ" to "ฺ", "ื" to "์", "ท" to "?",
        "ม" to "ฒ", "ใ" to "ฬ", "ฝ" to "ฦ"
    )

    // Small hint in the top-right corner of a key; long-press types it (like Gboard).
    private val thaiHints = mapOf(
        "ๅ" to "1", "/" to "2", "-" to "3", "ภ" to "4", "ถ" to "5",
        "ค" to "6", "ต" to "7", "จ" to "8", "ข" to "9", "ช" to "0",
        "ๆ" to "=", "ไ" to "@", "ำ" to "#", "พ" to "฿", "ะ" to "-",
        "ร" to "&", "น" to "_", "ย" to "+", "บ" to "(", "ล" to ")",
        "ฟ" to "\\", "ห" to "%", "ก" to "*", "ด" to "\"", "เ" to "'",
        "า" to ":", "ส" to ";", "ว" to "!", "ง" to "?", "ฃ" to "/",
        "ผ" to "[", "ป" to "]", "แ" to "{", "อ" to "}",
        "ท" to "~", "ม" to "`", "ใ" to "<", "ฝ" to ">"
    )
    private val englishHints = mapOf(
        "q" to "%", "w" to "\\", "e" to "|", "r" to "=", "t" to "[",
        "y" to "]", "u" to "<", "i" to ">", "o" to "{", "p" to "}",
        "a" to "@", "s" to "#", "d" to "$", "f" to "_", "g" to "&",
        "h" to "-", "j" to "+", "k" to "(", "l" to ")",
        "z" to "*", "x" to "\"", "c" to "'", "v" to ":", "b" to ";",
        "n" to "!", "m" to "?"
    )

    private val numberRow = listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "0")
    private val englishRows = listOf(
        listOf("q", "w", "e", "r", "t", "y", "u", "i", "o", "p"),
        listOf("a", "s", "d", "f", "g", "h", "j", "k", "l"),
        listOf("z", "x", "c", "v", "b", "n", "m")
    )
    private val symbolRows = listOf(
        numberRow,
        listOf("@", "#", "$", "_", "&", "-", "+", "(", ")", "/"),
        listOf("*", "\"", "'", ":", ";", "!", "?")
    )
    private val moreSymbolRows = listOf(
        listOf("~", "`", "|", "•", "√", "π", "÷", "×", "§", "∆"),
        listOf("£", "¢", "€", "¥", "^", "°", "=", "{", "}", "\\"),
        listOf("%", "©", "®", "™", "✓", "[", "]")
    )
    private val padRows = listOf(
        listOf("1", "2", "3", "-"),
        listOf("4", "5", "6", "."),
        listOf("7", "8", "9", ",")
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

    // A full-screen extract view in landscape hides the app being typed into.
    override fun onEvaluateFullscreenMode(): Boolean = false

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        if (!::keyboard.isInitialized) {
            shifted = false
            return
        }
        val previousLanguage = language
        loadSettings()
        if (restarting && previousLanguage in enabledLanguages()) {
            language = previousLanguage
        }
        if (!restarting) {
            fieldKind = fieldKindOf(info)
            symbols = false
            symbolPage = 0
            shiftLocked = false
            numberPad = fieldKind == FieldKind.NUMBER || fieldKind == FieldKind.PHONE
            val latinOnly = fieldKind == FieldKind.EMAIL ||
                fieldKind == FieldKind.URL ||
                fieldKind == FieldKind.PASSWORD
            if (latinOnly && Language.ENGLISH in enabledLanguages()) {
                language = Language.ENGLISH
            }
        }
        if (!shiftLocked) shifted = computeAutoShift()
        renderKeyboard()
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        stopDeleteRepeat()
        super.onFinishInputView(finishingInput)
    }

    override fun onDestroy() {
        stopDeleteRepeat()
        super.onDestroy()
    }

    private fun fieldKindOf(info: EditorInfo?): FieldKind {
        val type = info?.inputType ?: return FieldKind.TEXT
        val variation = type and InputType.TYPE_MASK_VARIATION
        return when (type and InputType.TYPE_MASK_CLASS) {
            InputType.TYPE_CLASS_NUMBER, InputType.TYPE_CLASS_DATETIME -> FieldKind.NUMBER
            InputType.TYPE_CLASS_PHONE -> FieldKind.PHONE
            InputType.TYPE_CLASS_TEXT -> when (variation) {
                InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS,
                InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS -> FieldKind.EMAIL
                InputType.TYPE_TEXT_VARIATION_URI -> FieldKind.URL
                InputType.TYPE_TEXT_VARIATION_PASSWORD,
                InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD,
                InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD -> FieldKind.PASSWORD
                else -> FieldKind.TEXT
            }
            else -> FieldKind.TEXT
        }
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
            numberPad -> padRows
            symbols && symbolPage == 1 -> moreSymbolRows
            symbols -> symbolRows
            language == Language.THAI -> thaiRows
            else -> listOf(numberRow) + englishRows
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
            val lastRow = index == rows.lastIndex && !numberPad
            if (lastRow) {
                if (symbols) {
                    addKey(line, if (symbolPage == 0) "=\\<" else "?123", 1.25f, KeyStyle.SPECIAL) {
                        symbolPage = 1 - symbolPage
                        renderKeyboard()
                    }
                } else {
                    val shiftLabel = when {
                        shiftLocked -> "⇪"
                        shifted -> "⇧"
                        else -> "↑"
                    }
                    val shiftStyle = if (shifted || shiftLocked) KeyStyle.ACTIVE else KeyStyle.SPECIAL
                    addKey(line, shiftLabel, 1.25f, shiftStyle) { onShiftTap() }
                }
            }
            row.forEach { key ->
                val label = when {
                    symbols || numberPad -> key
                    language == Language.THAI && shifted -> thaiShift[key] ?: key
                    language == Language.ENGLISH && shifted -> key.uppercase()
                    else -> key
                }
                val hint = when {
                    symbols || numberPad -> null
                    language == Language.THAI -> thaiHints[key]
                    else -> englishHints[key]
                }
                addKey(line, label, 1f, KeyStyle.NORMAL, hint = hint) { typeText(label) }
            }
            if (lastRow) {
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
        if (numberPad) {
            addKey(bottom, "?123", 1.25f, KeyStyle.SPECIAL) {
                numberPad = false
                symbols = true
                symbolPage = 0
                renderKeyboard()
            }
            addKey(bottom, "ABC", 1.25f, KeyStyle.SPECIAL) {
                numberPad = false
                symbols = false
                if (!shiftLocked) shifted = computeAutoShift()
                renderKeyboard()
            }
            addKey(bottom, "0", 2f, KeyStyle.NORMAL) { typeText("0") }
            addKey(bottom, "⌫", 1.25f, KeyStyle.SPECIAL) { deleteOne() }
            addKey(bottom, enterLabel(), 1.3f, KeyStyle.ENTER) { sendEnter() }
        } else {
            val morePage = symbols && symbolPage == 1
            val leftSide = when {
                morePage -> "<"
                !symbols && fieldKind == FieldKind.EMAIL -> "@"
                !symbols && fieldKind == FieldKind.URL -> "/"
                else -> ","
            }
            val rightSide = if (morePage) ">" else "."
            val spaceLabel = when {
                symbols -> "space"
                language == Language.THAI -> "ไทย"
                else -> "English"
            }
            addKey(bottom, if (symbols) "ABC" else "?123", 1.25f, KeyStyle.SPECIAL) {
                symbols = !symbols
                symbolPage = 0
                if (!shiftLocked) shifted = computeAutoShift()
                renderKeyboard()
            }
            addKey(bottom, leftSide, 0.7f, KeyStyle.NORMAL) { typeText(leftSide) }
            if (symbols) {
                addKey(bottom, "12/34", 1f, KeyStyle.SPECIAL) {
                    symbols = false
                    numberPad = true
                    renderKeyboard()
                }
            } else {
                addKey(bottom, "🌐", 1f, KeyStyle.SPECIAL, longPress = { showKeyboardPicker() }) {
                    switchLanguage()
                }
            }
            addKey(bottom, spaceLabel, 3.65f, KeyStyle.SPACE) { typeText(" ") }
            addKey(bottom, rightSide, 0.7f, KeyStyle.NORMAL) { typeText(rightSide) }
            addKey(bottom, enterLabel(), 1.3f, KeyStyle.ENTER) { sendEnter() }
        }
        keyboard.addView(bottom, LinearLayout.LayoutParams(-1, rowHeight))
    }

    private enum class KeyStyle { NORMAL, SPECIAL, ACTIVE, SPACE, ENTER }

    private fun addKey(
        row: LinearLayout,
        label: String,
        weight: Float,
        style: KeyStyle,
        hint: String? = null,
        longPress: (() -> Unit)? = null,
        action: () -> Unit
    ) {
        val key = FrameLayout(this).apply {
            background = GradientDrawable().apply {
                setColor(when (style) {
                    KeyStyle.NORMAL -> Color.WHITE
                    KeyStyle.SPECIAL -> Color.rgb(194, 202, 216)
                    KeyStyle.ACTIVE -> Color.rgb(143, 163, 209)
                    KeyStyle.SPACE -> Color.WHITE
                    KeyStyle.ENTER -> Color.rgb(91, 119, 180)
                })
                cornerRadius = dp(7).toFloat()
            }
            contentDescription = when {
                style == KeyStyle.SPACE -> "Space"
                label == "🌐" -> "Switch language"
                label == "⌫" -> "Delete"
                label == "↵" -> "Enter"
                label == "⇧" || label == "↑" -> "Shift"
                label == "⇪" -> "Caps lock"
                label == "=\\<" -> "More symbols"
                label == "12/34" -> "Number pad"
                label == "?123" -> "Numbers and symbols"
                label == "ABC" -> "Letters"
                else -> label
            }
        }
        key.addView(TextView(this).apply {
            text = label
            gravity = Gravity.CENTER
            textSize = when {
                style == KeyStyle.SPACE -> 12f
                label.length > 4 -> 11f
                label.length > 1 -> 14f
                else -> 20f
            }
            setTextColor(if (style == KeyStyle.ENTER) Color.WHITE else Color.rgb(34, 39, 49))
            typeface = Typeface.DEFAULT
            isSingleLine = true
        }, FrameLayout.LayoutParams(-1, -1))
        if (hint != null) {
            key.addView(TextView(this).apply {
                text = hint
                textSize = 10f
                setTextColor(Color.rgb(105, 113, 128))
                isSingleLine = true
            }, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.END).apply {
                topMargin = dp(2)
                marginEnd = dp(4)
            })
        }

        val longAction: (() -> Unit)? = longPress ?: hint?.let { value -> { typeText(value) } }
        key.setOnClickListener {
            key.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            action()
        }
        if (label == "⌫") {
            key.setOnLongClickListener {
                stopDeleteRepeat()
                deleteOne(updateShift = false)
                val repeat = object : Runnable {
                    override fun run() {
                        deleteOne(updateShift = false)
                        deleteHandler.postDelayed(this, 55)
                    }
                }
                deleteRepeat = repeat
                deleteHandler.postDelayed(repeat, 280)
                true
            }
            key.setOnTouchListener { _, event ->
                if (event.action == MotionEvent.ACTION_UP ||
                    event.action == MotionEvent.ACTION_CANCEL) {
                    val wasRepeating = deleteRepeat != null
                    stopDeleteRepeat()
                    // Re-rendering mid-repeat would drop the touch, so refresh shift afterwards.
                    if (wasRepeating) keyboard.post { afterTextChanged() }
                }
                false
            }
        } else if (longAction != null) {
            key.setOnLongClickListener {
                key.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                longAction()
                true
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

    private fun onShiftTap() {
        val now = SystemClock.uptimeMillis()
        when {
            shiftLocked -> {
                shiftLocked = false
                shifted = false
            }
            shifted && now - lastShiftTap < 400L -> shiftLocked = true
            else -> shifted = !shifted
        }
        lastShiftTap = now
        renderKeyboard()
    }

    private fun switchLanguage() {
        val enabled = enabledLanguages()
        if (enabled.size < 2) {
            showKeyboardPicker()
            return
        }
        language = enabled[(enabled.indexOf(language) + 1) % enabled.size]
        preferences.edit().putString("current_language", language.code).apply()
        symbols = false
        symbolPage = 0
        numberPad = false
        shiftLocked = false
        shifted = computeAutoShift()
        renderKeyboard()
    }

    private fun showKeyboardPicker() {
        val manager = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
        manager.showInputMethodPicker()
    }

    // English capitalises after sentence ends when the field asks for it.
    private fun computeAutoShift(): Boolean {
        if (symbols || numberPad || language != Language.ENGLISH) return false
        val type = currentInputEditorInfo?.inputType ?: return false
        if (type == InputType.TYPE_NULL) return false
        return (currentInputConnection?.getCursorCapsMode(type) ?: 0) != 0
    }

    private fun afterTextChanged() {
        if (symbols || numberPad || shiftLocked) return
        val next = computeAutoShift()
        if (next != shifted) {
            shifted = next
            renderKeyboard()
        }
    }

    private fun typeText(value: String) {
        currentInputConnection?.commitText(value, 1)
        afterTextChanged()
    }

    private fun deleteOne(updateShift: Boolean = true) {
        val connection = currentInputConnection ?: return
        val selection = connection.getSelectedText(0)
        if (!selection.isNullOrEmpty()) {
            connection.commitText("", 1)
        } else if (Build.VERSION.SDK_INT >= 24) {
            if (!connection.deleteSurroundingTextInCodePoints(1, 0)) {
                connection.deleteSurroundingText(1, 0)
            }
        } else {
            connection.deleteSurroundingText(1, 0)
        }
        if (updateShift) afterTextChanged()
    }

    private fun enterLabel(): String {
        val options = currentInputEditorInfo?.imeOptions ?: 0
        if ((options and EditorInfo.IME_FLAG_NO_ENTER_ACTION) != 0) return "↵"
        return when (options and EditorInfo.IME_MASK_ACTION) {
            EditorInfo.IME_ACTION_GO -> "Go"
            EditorInfo.IME_ACTION_SEARCH -> "Search"
            EditorInfo.IME_ACTION_SEND -> "Send"
            EditorInfo.IME_ACTION_NEXT -> "Next"
            EditorInfo.IME_ACTION_DONE -> "Done"
            else -> "↵"
        }
    }

    private fun sendEnter() {
        val options = currentInputEditorInfo?.imeOptions ?: 0
        val action = options and EditorInfo.IME_MASK_ACTION
        val noEnterAction = (options and EditorInfo.IME_FLAG_NO_ENTER_ACTION) != 0
        if (!noEnterAction &&
            action != EditorInfo.IME_ACTION_NONE &&
            action != EditorInfo.IME_ACTION_UNSPECIFIED) {
            currentInputConnection?.performEditorAction(action)
        } else {
            typeText("\n")
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
