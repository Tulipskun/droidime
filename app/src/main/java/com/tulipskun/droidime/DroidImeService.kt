package com.tulipskun.droidime

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.inputmethodservice.InputMethodService
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.text.InputType
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.SoundEffectConstants
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityNodeProvider
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager

class DroidImeService : InputMethodService() {
    private enum class Language(val code: String) {
        THAI("th"),
        ENGLISH("en")
    }

    private enum class FieldKind { TEXT, EMAIL, URL, PASSWORD, NUMBER, PHONE }

    private enum class KeyStyle { NORMAL, SPECIAL, ACTIVE, SPACE, ENTER, TOOLBAR, SUGGESTION }

    private enum class ToolIcon { GRID, UNDO, TRANSLATE, CLIPBOARD, SETTINGS, SELECT }

    // Vector icons drawn on keys instead of font glyphs, so they look the same on every device
    // (the globe emoji, for one, renders as a coloured picture on Android).
    private enum class KeyGlyph { SHIFT, SHIFT_ON, CAPS, DELETE, ENTER, GLOBE, NUMBER_PAD }

    // One tappable word suggestion: replace the last `replaceLen` characters with `text + append`.
    private data class Suggestion(val text: String, val replaceLen: Int, val append: String)

    // Words sorted for binary-search prefix lookup, with a frequency per word.
    private class Dictionary(val words: Array<String>, val freqs: IntArray) {
        private fun lowerBound(prefix: String): Int {
            var lo = 0
            var hi = words.size
            while (lo < hi) {
                val mid = (lo + hi) ushr 1
                if (words[mid] < prefix) lo = mid + 1 else hi = mid
            }
            return lo
        }

        // Indices of the `limit` most frequent words that start with `prefix` and are longer.
        fun complete(prefix: String, limit: Int): List<Int> {
            val best = ArrayList<Int>()
            var i = lowerBound(prefix)
            while (i < words.size && words[i].startsWith(prefix)) {
                if (words[i].length > prefix.length) {
                    var pos = best.size
                    while (pos > 0 && freqs[best[pos - 1]] < freqs[i]) pos--
                    if (pos < limit) {
                        best.add(pos, i)
                        if (best.size > limit) best.removeAt(limit)
                    }
                }
                i++
            }
            return best
        }
    }

    private class Palette(
        val background: Int,
        val key: Int,
        val special: Int,
        val active: Int,
        val enter: Int,
        val text: Int,
        val enterText: Int,
        val hint: Int
    )

    private val lightPalette = Palette(
        background = Color.rgb(218, 222, 230),
        key = Color.WHITE,
        special = Color.rgb(194, 202, 216),
        active = Color.rgb(143, 163, 209),
        enter = Color.rgb(91, 119, 180),
        text = Color.rgb(34, 39, 49),
        enterText = Color.WHITE,
        hint = Color.rgb(105, 113, 128)
    )
    // Dark values are sampled from Gboard's dark theme: special keys (shift, delete, ?123, enter,
    // comma, period) are lighter than letter keys, and the enter key is not accent-coloured.
    private val darkPalette = Palette(
        background = Color.rgb(31, 31, 31),
        key = Color.rgb(57, 57, 57),
        special = Color.rgb(71, 71, 71),
        active = Color.rgb(86, 104, 150),
        enter = Color.rgb(71, 71, 71),
        text = Color.rgb(227, 227, 227),
        enterText = Color.rgb(227, 227, 227),
        hint = Color.rgb(199, 199, 199)
    )

    private var suggestions: List<Suggestion> = emptyList()
    @Volatile private var dictEn: Dictionary? = null
    @Volatile private var dictTh: Dictionary? = null

    private var palette = lightPalette
    private var darkTheme = false
    private var appliedDark: Boolean? = null

    private var language = Language.THAI
    private var fieldKind = FieldKind.TEXT
    private var shifted = false
    private var shiftLocked = false
    private var lastShiftTap = 0L
    private var lastSpaceTap = 0L
    private var symbols = false
    private var symbolPage = 0
    private var numberPad = false
    private var navigationInset = 0
    private var keyboardView: KeyboardCanvasView? = null
    private val deleteHandler = Handler(Looper.getMainLooper())
    private var deleteRepeat: Runnable? = null
    private var deleteHold: Runnable? = null
    private var deleteSpeedMs = DELETE_START_MS

    private val thaiRows = listOf(
        listOf("ๅ", "/", "_", "ภ", "ถ", "ุ", "ึ", "ค", "ต", "จ", "ข", "ช"),
        listOf("ๆ", "ไ", "ำ", "พ", "ะ", "ั", "ี", "ร", "น", "ย", "บ", "ล"),
        listOf("ฟ", "ห", "ก", "ด", "เ", "้", "่", "า", "ส", "ว", "ง", "ฃ"),
        listOf("ผ", "ป", "แ", "อ", "ิ", "ื", "ท", "ม", "ใ", "ฝ")
    )
    private val thaiShift = mapOf(
        "ๅ" to "+", "/" to "๑", "_" to "๒", "ภ" to "๓", "ถ" to "๔",
        "ุ" to "ู", "ึ" to "฿", "ค" to "๕", "ต" to "๖", "จ" to "๗", "ข" to "๘", "ช" to "๙",
        "ๆ" to "๐", "ไ" to "\"", "ำ" to "ฎ", "พ" to "ฑ", "ะ" to "ธ",
        "ั" to "ํ", "ี" to "๊", "ร" to "ณ", "น" to "ฯ", "ย" to "ญ",
        "บ" to "ฐ", "ล" to ",", "ฟ" to "ฤ", "ห" to "ฆ", "ก" to "ฏ",
        "ด" to "โ", "เ" to "ฌ", "้" to "็", "่" to "๋", "า" to "ษ",
        "ส" to "ศ", "ว" to "ซ", "ง" to ".", "ฃ" to "ฅ", "ผ" to "(", "ป" to ")",
        "แ" to "ฉ", "อ" to "ฮ", "ิ" to "ฺ", "ื" to "์", "ท" to "?",
        "ม" to "ฒ", "ใ" to "ฬ", "ฝ" to "ฦ"
    )

    // Small hint in the top-left corner of an English key; long-press types it.
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

    private val settingsHandler = Handler(Looper.getMainLooper())

    // Settings changes must reach the live keyboard without reopening the field.
    private val prefListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == null || key in SETTINGS_KEYS) {
            settingsHandler.post {
                loadSettings()
                refreshTheme()
                suggestions = computeSuggestions()
                keyboardView?.requestLayout()
                keyboardView?.rebuild()
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        preferences.registerOnSharedPreferenceChangeListener(prefListener)
        Thread {
            dictEn = loadDictionary("words_en.txt")
            dictTh = loadDictionary("words_th.txt")
            settingsHandler.post { updateSuggestions() }
        }.start()
    }

    // Assets are "word<TAB>frequency" lines; sort by word so prefixes can be binary-searched.
    private fun loadDictionary(name: String): Dictionary? = try {
        val entries = ArrayList<Pair<String, Int>>()
        assets.open(name).bufferedReader(Charsets.UTF_8).useLines { lines ->
            for (line in lines) {
                val tab = line.indexOf('\t')
                if (tab > 0) entries.add(line.substring(0, tab) to (line.substring(tab + 1).toIntOrNull() ?: 1))
            }
        }
        entries.sortBy { it.first }
        Dictionary(Array(entries.size) { entries[it].first }, IntArray(entries.size) { entries[it].second })
    } catch (error: Exception) {
        null
    }

    @Suppress("DEPRECATION")
    private val vibrator: Vibrator? by lazy {
        if (Build.VERSION.SDK_INT >= 31) {
            getSystemService(VibratorManager::class.java)?.defaultVibrator
        } else {
            getSystemService(VIBRATOR_SERVICE) as? Vibrator
        }
    }

    override fun onCreateInputView(): View {
        val view = KeyboardCanvasView(this)
        keyboardView = view
        getWindow().window?.let { imeWindow ->
            if (Build.VERSION.SDK_INT >= 29) {
                imeWindow.isNavigationBarContrastEnforced = false
            }
        }
        loadSettings(view)
        refreshTheme()
        view.rebuild()
        view.requestApplyInsets()
        return view
    }

    // A full-screen extract view in landscape hides the app being typed into.
    override fun onEvaluateFullscreenMode(): Boolean = false

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        if (keyboardView == null) {
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
        preferences.unregisterOnSharedPreferenceChangeListener(prefListener)
        stopDeleteRepeat()
        keyboardView = null
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

    private fun loadSettings(view: View? = keyboardView) {
        val enabled = enabledLanguages()
        val saved = preferences.getString("current_language", language.code)
        language = enabled.firstOrNull { it.code == saved } ?: enabled.first()
        val height = preferences.getInt("keyboard_height_dp", 250).coerceIn(220, 360)
        val target = view ?: return
        val params = target.layoutParams ?: ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            dp(height + STRIP_EXTRA_DP)
        )
        params.height = dp(height + STRIP_EXTRA_DP)
        target.layoutParams = params
    }

    private fun enabledLanguages(): List<Language> {
        val thai = preferences.getBoolean("language_th", true)
        val english = preferences.getBoolean("language_en", true)
        return buildList {
            if (thai) add(Language.THAI)
            if (english) add(Language.ENGLISH)
        }.ifEmpty { listOf(Language.THAI) }
    }

    private fun refreshTheme() {
        darkTheme = when (preferences.getString("theme", "system")) {
            "dark" -> true
            "light" -> false
            else -> (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
                Configuration.UI_MODE_NIGHT_YES
        }
        palette = if (darkTheme) darkPalette else lightPalette
        if (appliedDark != darkTheme) {
            appliedDark = darkTheme
            getWindow().window?.let { imeWindow ->
                imeWindow.navigationBarColor = palette.background
                if (Build.VERSION.SDK_INT >= 26) {
                    val decor = imeWindow.decorView
                    val flag = View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
                    decor.systemUiVisibility = if (darkTheme) {
                        decor.systemUiVisibility and flag.inv()
                    } else {
                        decor.systemUiVisibility or flag
                    }
                }
            }
        }
    }

    private fun renderKeyboard() {
        refreshTheme()
        suggestions = computeSuggestions()
        keyboardView?.rebuild()
    }

    override fun onUpdateSelection(
        oldSelStart: Int, oldSelEnd: Int, newSelStart: Int, newSelEnd: Int,
        candidatesStart: Int, candidatesEnd: Int
    ) {
        super.onUpdateSelection(oldSelStart, oldSelEnd, newSelStart, newSelEnd, candidatesStart, candidatesEnd)
        updateSuggestions()
    }

    private fun updateSuggestions() {
        val next = computeSuggestions()
        if (next != suggestions) {
            suggestions = next
            keyboardView?.rebuild()
        }
    }

    private fun isThai(c: Char) = c in '\u0E01'..'\u0E5B'

    // Suggestions follow the characters before the cursor, whichever keyboard language is shown.
    private fun computeSuggestions(): List<Suggestion> {
        if (!preferences.getBoolean("suggestions", true)) return emptyList()
        if (symbols || numberPad || fieldKind != FieldKind.TEXT) return emptyList()
        val type = currentInputEditorInfo?.inputType ?: return emptyList()
        if ((type and InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS) != 0) return emptyList()
        val connection = currentInputConnection ?: return emptyList()
        val after = connection.getTextAfterCursor(1, 0)
        if (!after.isNullOrEmpty() && after[0].isLetterOrDigit()) return emptyList()
        val before = connection.getTextBeforeCursor(24, 0)?.toString() ?: return emptyList()
        if (before.isEmpty()) return emptyList()
        return if (isThai(before.last())) thaiSuggestions(before) else englishSuggestions(before)
    }

    private fun englishSuggestions(before: String): List<Suggestion> {
        val dict = dictEn ?: return emptyList()
        var start = before.length
        while (start > 0 && (before[start - 1] in 'a'..'z' || before[start - 1] in 'A'..'Z' || before[start - 1] == '\'')) start--
        val typed = before.substring(start)
        if (typed.isEmpty() || typed.length > 20) return emptyList()
        val found = dict.complete(typed.lowercase(), if (typed.length >= 2) 2 else 3)
        if (found.isEmpty()) return emptyList()
        val allCaps = typed.length > 1 && typed.all { it.isUpperCase() }
        val capital = typed[0].isUpperCase()
        val out = ArrayList<Suggestion>()
        // The word as typed stays available as the first choice, like Gboard.
        if (typed.length >= 2) out.add(Suggestion(typed, typed.length, " "))
        for (index in found) {
            val word = dict.words[index]
            val shown = when {
                allCaps -> word.uppercase()
                capital -> word.replaceFirstChar { it.uppercase() }
                else -> word
            }
            out.add(Suggestion(shown, typed.length, " "))
        }
        return out
    }

    // Thai has no spaces, so try the longest trailing run of letters first and fall back to
    // shorter endings until three different words are found.
    private fun thaiSuggestions(before: String): List<Suggestion> {
        val dict = dictTh ?: return emptyList()
        var start = before.length
        while (start > 0 && isThai(before[start - 1]) && before.length - start < 12) start--
        val run = before.substring(start)
        val out = ArrayList<Suggestion>()
        for (k in run.length downTo 2) {
            val suffix = run.substring(run.length - k)
            if (suffix[0] in THAI_NON_LEADING) continue
            for (index in dict.complete(suffix, 3)) {
                val word = dict.words[index]
                if (out.none { it.text == word }) out.add(Suggestion(word, k, ""))
                if (out.size == 3) return out
            }
        }
        return out
    }

    private fun applySuggestion(suggestion: Suggestion) {
        val connection = currentInputConnection ?: return
        connection.beginBatchEdit()
        connection.deleteSurroundingText(suggestion.replaceLen, 0)
        connection.commitText(suggestion.text + suggestion.append, 1)
        connection.endBatchEdit()
        lastSpaceTap = 0L
        afterTextChanged()
    }

    private fun sendCtrl(keyCode: Int) {
        val connection = currentInputConnection ?: return
        val now = SystemClock.uptimeMillis()
        connection.sendKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_DOWN, keyCode, 0, KeyEvent.META_CTRL_ON))
        connection.sendKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_UP, keyCode, 0, KeyEvent.META_CTRL_ON))
        afterTextChanged()
    }

    private fun pasteClipboard() {
        currentInputConnection?.performContextMenuAction(android.R.id.paste)
        afterTextChanged()
    }

    private fun selectAllText() {
        currentInputConnection?.performContextMenuAction(android.R.id.selectAll)
    }

    @Suppress("DEPRECATION")
    private fun keyFeedback(view: View) {
        if (preferences.getBoolean("haptics", true)) {
            val duration = preferences.getInt("haptic_ms", 15).coerceIn(5, 50).toLong()
            val device = vibrator
            if (device != null && device.hasVibrator()) {
                if (Build.VERSION.SDK_INT >= 26) {
                    device.vibrate(
                        VibrationEffect.createOneShot(duration, VibrationEffect.DEFAULT_AMPLITUDE)
                    )
                } else {
                    device.vibrate(duration)
                }
            }
        }
        if (preferences.getBoolean("key_sound", false)) {
            view.playSoundEffect(SoundEffectConstants.CLICK)
        }
    }

    private fun stopDeleteRepeat() {
        deleteHold?.let { deleteHandler.removeCallbacks(it) }
        deleteHold = null
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

    private fun openSettings() {
        val intent = Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        startActivity(intent)
    }

    // Toolbar emoji key: tap types a comma, long-press opens the picker (not built yet).
    private fun showEmoji() {
        android.widget.Toast.makeText(this, "Emoji picker coming soon", android.widget.Toast.LENGTH_SHORT).show()
    }

    // English capitalises after sentence ends when the field asks for it.
    private fun computeAutoShift(): Boolean {
        if (!preferences.getBoolean("auto_caps", true)) return false
        if (symbols || numberPad || language != Language.ENGLISH) return false
        val type = currentInputEditorInfo?.inputType ?: return false
        if (type == InputType.TYPE_NULL) return false
        return (currentInputConnection?.getCursorCapsMode(type) ?: 0) != 0
    }

    private fun afterTextChanged() {
        if (!(symbols || numberPad || shiftLocked)) {
            val next = computeAutoShift()
            if (next != shifted) {
                shifted = next
                renderKeyboard()
                return
            }
        }
        updateSuggestions()
    }

    private fun typeText(value: String) {
        currentInputConnection?.commitText(value, 1)
        afterTextChanged()
    }

    // Two quick spaces after a word become ". " (English only).
    private fun typeSpace() {
        val connection = currentInputConnection ?: return
        val now = SystemClock.uptimeMillis()
        if (language == Language.ENGLISH &&
            preferences.getBoolean("double_space_period", true) &&
            now - lastSpaceTap < 400L) {
            val before = connection.getTextBeforeCursor(2, 0)
            if (before != null && before.length == 2 && before[1] == ' ' && before[0].isLetterOrDigit()) {
                connection.deleteSurroundingText(1, 0)
                connection.commitText(". ", 1)
                lastSpaceTap = 0L
                afterTextChanged()
                return
            }
        }
        lastSpaceTap = now
        typeText(" ")
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
        if ((options and EditorInfo.IME_FLAG_NO_ENTER_ACTION) != 0) return ENTER
        return when (options and EditorInfo.IME_MASK_ACTION) {
            EditorInfo.IME_ACTION_GO -> "Go"
            EditorInfo.IME_ACTION_SEARCH -> "Search"
            EditorInfo.IME_ACTION_SEND -> "Send"
            EditorInfo.IME_ACTION_NEXT -> "Next"
            EditorInfo.IME_ACTION_DONE -> "Done"
            else -> ENTER
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

    private fun dpf(value: Float): Float = value * resources.displayMetrics.density

    // A single drawn key. The rect is filled in during layout; null spacer slots have no cell.
    private class Cell(
        val label: String,
        val style: KeyStyle,
        val hint: String?,
        val action: () -> Unit,
        val longAction: (() -> Unit)? = null,
        val icon: ToolIcon? = null,
        val emojiFace: Boolean = false,
        val isDelete: Boolean = false,
        val glyph: KeyGlyph? = null,
        val pill: Boolean = false,
        val rect: RectF = RectF(),
        // Gap-free hit area: tiles the whole keyboard so no tap lands on dead space.
        val touchRect: RectF = RectF()
    ) {
        val contentDescription: String = describe(label, style)

        companion object {
            fun describe(label: String, style: KeyStyle): String = when {
                style == KeyStyle.SPACE -> "Space"
                label == GLOBE -> "Switch language"
                label == DELETE -> "Delete"
                label == ENTER -> "Enter"
                label == SHIFT -> "Shift"
                label == SHIFT_ON -> "Shift"
                label == CAPS -> "Caps lock"
                label == MORE_SYMBOLS -> "More symbols"
                label == NUMBER_PAD -> "Number pad"
                label == "?123" -> "Numbers and symbols"
                label == "ABC" -> "Letters"
                else -> label
            }
        }
    }

    // Single-view Canvas keyboard: keys are drawn, not child views, so relayouts stay cheap.
    private inner class KeyboardCanvasView(context: Context) : View(context) {
        private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textAlign = Paint.Align.CENTER
            typeface = Typeface.DEFAULT
        }
        private val hintPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textAlign = Paint.Align.RIGHT
            typeface = Typeface.DEFAULT
        }
        private val iconStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
        private val iconFill = Paint(Paint.ANTI_ALIAS_FLAG)
        private val mediumFace: Typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        private val shape = GradientDrawable()
        private val cornerRadius = dpf(6f)

        var cells: List<Cell> = emptyList()
            private set
        private var pressed: Cell? = null
        private val pressHandler = Handler(Looper.getMainLooper())
        private var longPressFired = false
        private var pendingLongPress: Runnable? = null

        init {
            setOnApplyWindowInsetsListener { _, insets ->
                val bottom = if (Build.VERSION.SDK_INT >= 30) {
                    insets.getInsets(WindowInsets.Type.navigationBars()).bottom
                } else {
                    insets.systemWindowInsetBottom
                }
                if (bottom != navigationInset) {
                    navigationInset = bottom
                    rebuild()
                }
                insets
            }
        }

        // A bare View has no intrinsic height, so honour the configured keyboard height
        // whenever the IME measures this view with anything other than an exact size.
        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val w = MeasureSpec.getSize(widthMeasureSpec)
            val desired = dp(preferences.getInt("keyboard_height_dp", 250).coerceIn(220, 360) + STRIP_EXTRA_DP)
            val h = if (MeasureSpec.getMode(heightMeasureSpec) == MeasureSpec.EXACTLY) {
                MeasureSpec.getSize(heightMeasureSpec)
            } else {
                desired
            }
            setMeasuredDimension(w, h)
        }

        fun rebuild() {
            if (width > 0 && height > 0) {
                layoutRows(buildRows())
            } else {
                cells = emptyList()
            }
            invalidate()
        }

        override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
            super.onSizeChanged(w, h, oldw, oldh)
            rebuild()
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            canvas.drawColor(palette.background)
            cells.forEach { drawCell(canvas, it) }
        }

        // Each row is a list of (weight, cell) slots; a null cell is an indent spacer.
        private fun buildRows(): List<List<Pair<Float, Cell?>>> {
            val numberRowEnglish = preferences.getBoolean("number_row_en", true)
            val numberRowThai = preferences.getBoolean("number_row_th", false)
            val rows = when {
                numberPad -> padRows
                symbols && symbolPage == 1 -> moreSymbolRows
                symbols -> symbolRows
                language == Language.THAI ->
                    if (numberRowThai) listOf(numberRow) + thaiRows else thaiRows
                else -> if (numberRowEnglish) listOf(numberRow) + englishRows else englishRows
            }
            val built = mutableListOf<List<Pair<Float, Cell?>>>()
            rows.forEachIndexed { index, row ->
                val lastRow = index == rows.lastIndex && !numberPad
                val slots = mutableListOf<Pair<Float, Cell?>>()
                if (row.size == 9) slots.add(0.5f to null)
                // English/symbols last rows have wide shift/delete (1.5); the Thai last row is a
                // 12-column grid where shift/delete must match the letters (1.0) to stay aligned.
                val specialWidth = if (row.size == 7) 1.5f else 1f
                if (lastRow) {
                    if (symbols) slots.add(specialWidth to pageKeyCell())
                    else slots.add(specialWidth to shiftCell())
                }
                row.forEach { key -> slots.add(1f to letterCell(key)) }
                if (lastRow) slots.add(specialWidth to deleteCell())
                if (row.size == 9) slots.add(0.5f to null)
                built.add(slots)
            }
            built.add(bottomRow())
            return built
        }

        // Top strip: word suggestions while typing a word, otherwise the tool icons.
        private fun stripCells(): List<Cell> {
            if (suggestions.isNotEmpty()) {
                return suggestions.map { s ->
                    Cell(s.text, KeyStyle.SUGGESTION, null, action = { applySuggestion(s) })
                }
            }
            fun tool(icon: ToolIcon, name: String, action: () -> Unit) =
                Cell(name, KeyStyle.TOOLBAR, null, icon = icon, action = action)
            return listOf(
                tool(ToolIcon.GRID, "Choose keyboard") { showKeyboardPicker() },
                tool(ToolIcon.UNDO, "Undo") { sendCtrl(KeyEvent.KEYCODE_Z) },
                tool(ToolIcon.TRANSLATE, "Switch language") { switchLanguage() },
                tool(ToolIcon.CLIPBOARD, "Paste") { pasteClipboard() },
                tool(ToolIcon.SETTINGS, "Keyboard settings") { openSettings() },
                tool(ToolIcon.SELECT, "Select all") { selectAllText() }
            )
        }

        // Suggestions share the width equally; tool icons sit at Gboard's measured positions
        // (centres at 6.7% + n * 17.3% of the width) with full-height touch columns.
        private fun placeStrip(strip: List<Cell>, out: MutableList<Cell>) {
            if (strip.isEmpty()) return
            val bandH = dpf(STRIP_BAND_DP)
            val centerY = bandH / 2f
            val w = width.toFloat()
            if (strip[0].style == KeyStyle.SUGGESTION) {
                val slot = w / strip.size
                strip.forEachIndexed { i, cell ->
                    cell.rect.set(slot * i, 0f, slot * (i + 1), bandH)
                    cell.touchRect.set(cell.rect)
                    out.add(cell)
                }
            } else {
                val half = dpf(12f)
                val slot = w * 0.1731f
                strip.forEachIndexed { i, cell ->
                    val cx = w * (0.0667f + 0.1731f * i)
                    cell.rect.set(cx - half, centerY - half, cx + half, centerY + half)
                    val left = if (i == 0) 0f else cx - slot / 2f
                    val right = if (i == strip.lastIndex) w else cx + slot / 2f
                    cell.touchRect.set(left, 0f, right, bandH)
                    out.add(cell)
                }
            }
        }

        private fun letterCell(key: String): Cell {
            val showHints = preferences.getBoolean("key_hints", true)
            val label = when {
                symbols || numberPad -> key
                language == Language.THAI && shifted -> thaiShift[key] ?: key
                language == Language.ENGLISH && shifted -> key.uppercase()
                else -> key
            }
            val hint = when {
                !showHints || symbols || numberPad -> null
                language == Language.THAI -> if (shifted) null else thaiShift[key]
                else -> englishHints[key]
            }
            val longPress = hint?.let { value: String -> { typeText(value) } }
            return Cell(label, KeyStyle.NORMAL, hint, action = { typeText(label) }, longAction = longPress)
        }

        private fun shiftCell(): Cell {
            val label = when {
                shiftLocked -> CAPS
                shifted -> SHIFT_ON
                else -> SHIFT
            }
            val style = if (shifted || shiftLocked) KeyStyle.ACTIVE else KeyStyle.SPECIAL
            val glyph = when {
                shiftLocked -> KeyGlyph.CAPS
                shifted -> KeyGlyph.SHIFT_ON
                else -> KeyGlyph.SHIFT
            }
            return Cell(label, style, null, glyph = glyph, action = { onShiftTap() })
        }

        private fun pageKeyCell(): Cell {
            val label = if (symbolPage == 0) MORE_SYMBOLS else "?123"
            return Cell(label, KeyStyle.SPECIAL, null, action = {
                symbolPage = 1 - symbolPage
                renderKeyboard()
            })
        }

        private fun deleteCell(): Cell =
            Cell(DELETE, KeyStyle.SPECIAL, null, isDelete = true, glyph = KeyGlyph.DELETE,
                action = { deleteOne() })

        private fun bottomRow(): List<Pair<Float, Cell?>> {
            val slots = mutableListOf<Pair<Float, Cell?>>()
            if (numberPad) {
                slots.add(1.25f to Cell("?123", KeyStyle.SPECIAL, null, pill = true, action = {
                    numberPad = false
                    symbols = true
                    symbolPage = 0
                    renderKeyboard()
                }))
                slots.add(1.25f to Cell("ABC", KeyStyle.SPECIAL, null, pill = true, action = {
                    numberPad = false
                    symbols = false
                    if (!shiftLocked) shifted = computeAutoShift()
                    renderKeyboard()
                }))
                slots.add(2f to Cell("0", KeyStyle.NORMAL, null, action = { typeText("0") }))
                slots.add(1.25f to deleteCell())
                slots.add(1.3f to enterCell())
            } else {
                val morePage = symbols && symbolPage == 1
                val leftSide = when {
                    morePage -> "<"
                    !symbols && fieldKind == FieldKind.EMAIL -> "@"
                    !symbols && fieldKind == FieldKind.URL -> "/"
                    else -> ","
                }
                val rightSide = if (morePage) ">" else "."
                // Gboard keeps the language name on the space bar on the symbol pages too.
                val spaceLabel = if (language == Language.THAI) "ไทย" else "English"
                slots.add(1.5f to Cell(if (symbols) "ABC" else "?123", KeyStyle.SPECIAL, null, pill = true, action = {
                    symbols = !symbols
                    symbolPage = 0
                    if (!shiftLocked) shifted = computeAutoShift()
                    renderKeyboard()
                }))
                val emojiKey = leftSide == ","
                slots.add(1f to Cell(
                    if (emojiKey) "" else leftSide,
                    KeyStyle.SPECIAL, null,
                    emojiFace = emojiKey,
                    action = { typeText(leftSide) },
                    longAction = if (emojiKey) ({ showEmoji() }) else null
                ))
                if (symbols) {
                    slots.add(1f to Cell(NUMBER_PAD, KeyStyle.NORMAL, null, glyph = KeyGlyph.NUMBER_PAD, action = {
                        symbols = false
                        numberPad = true
                        renderKeyboard()
                    }))
                } else {
                    slots.add(1f to Cell(GLOBE, KeyStyle.NORMAL, null, glyph = KeyGlyph.GLOBE,
                        longAction = { showKeyboardPicker() },
                        action = { switchLanguage() }))
                }
                slots.add(4f to Cell(spaceLabel, KeyStyle.SPACE, null, action = { typeSpace() }))
                slots.add(1f to Cell(rightSide, KeyStyle.SPECIAL, null, action = { typeText(rightSide) }))
                slots.add(1.5f to enterCell())
            }
            return slots
        }

        private fun enterCell(): Cell {
            val label = enterLabel()
            return Cell(label, KeyStyle.ENTER, null, pill = true,
                glyph = if (label == ENTER) KeyGlyph.ENTER else null,
                action = { sendEnter() })
        }

        private fun numberRowOn(): Boolean =
            if (language == Language.THAI) preferences.getBoolean("number_row_th", false)
            else preferences.getBoolean("number_row_en", true)

        // Row heights and the gap between rows as measured on Gboard (1080px-wide screen, block
        // 678px tall): the key block always fills the same height, so pages with fewer rows get
        // taller keys and wider gaps rather than leaving empty space. Returns the content rows'
        // heights (the bottom row is added separately) and the gap, in reference pixels.
        private fun rowSpec(contentRows: Int, numberRowFirst: Boolean): Pair<List<Float>, Float> =
            when {
                contentRows == 3 -> List(3) { 134.5f } to 50f
                contentRows == 4 && numberRowFirst -> listOf(110f, 118f, 118f, 118f) to 22.5f
                contentRows == 4 -> List(4) { 115.5f } to 23f
                else -> {
                    val gap = 20f
                    val h = (REF_BLOCK_H - REF_BOTTOM_ROW_H - gap * contentRows) / contentRows
                    List(contentRows) { h } to gap
                }
            }

        private fun layoutRows(rows: List<List<Pair<Float, Cell?>>>) {
            val flat = mutableListOf<Cell>()
            if (rows.isEmpty()) {
                cells = flat
                return
            }
            val padX = dpf(6.7f)
            val padTop = dpf(STRIP_TOP_DP)
            val padBottom = dpf(8f) + navigationInset
            val availW = (width - padX * 2f).coerceAtLeast(1f)
            val availH = (height - padTop - padBottom).coerceAtLeast(1f)
            val contentRows = rows.size - 1
            val letterPage = !numberPad && !symbols
            val (contentHeights, refGap) = rowSpec(contentRows, letterPage && numberRowOn())
            val refHeights = contentHeights + REF_BOTTOM_ROW_H
            val scale = availH / (refHeights.sum() + refGap * contentRows)
            val gap = refGap * scale
            val lastRow = rows.lastIndex
            placeStrip(stripCells(), flat)
            var nextTop = padTop
            rows.forEachIndexed { index, row ->
                val top = nextTop
                val bottom = top + refHeights[index] * scale
                nextTop = bottom + gap
                // Vertical touch band reaches to the gap midpoint so rows tile with no dead strip;
                // the first and last bands extend to the view edges.
                val bandTop = if (index == 0) dpf(STRIP_BAND_DP) else top - gap / 2f
                val bandBottom = if (index == lastRow) height.toFloat() else bottom + gap / 2f
                val totalWeight = row.sumOf { it.first.toDouble() }.toFloat()
                if (totalWeight <= 0f) return@forEachIndexed
                val unit = availW / totalWeight
                // Gboard insets each key by a fixed share of the row's unit width, so the gap
                // between keys is ~12px in the 12-key Thai rows and ~14px in the 10-key rows.
                val inset = unit * KEY_INSET
                val firstReal = row.indexOfFirst { it.second != null }
                val lastReal = row.indexOfLast { it.second != null }
                var x = padX
                row.forEachIndexed { slotIndex, (weight, cell) ->
                    val slotRight = x + weight * unit
                    if (cell != null) {
                        cell.rect.set(x + inset, top, slotRight - inset, bottom)
                        // Horizontal touch spans the full slot; the outermost keys also absorb
                        // the side padding and any indent spacer so the whole row is clickable.
                        val touchLeft = if (slotIndex == firstReal) 0f else x
                        val touchRight = if (slotIndex == lastReal) width.toFloat() else slotRight
                        cell.touchRect.set(touchLeft, bandTop, touchRight, bandBottom)
                        flat.add(cell)
                    }
                    x = slotRight
                }
            }
            cells = flat
        }

        private fun drawCell(canvas: Canvas, cell: Cell) {
            val colors = palette
            if (cell.style == KeyStyle.TOOLBAR) {
                drawToolbarIcon(canvas, cell, colors)
                return
            }
            if (cell.style == KeyStyle.SUGGESTION) {
                drawSuggestion(canvas, cell, colors)
                return
            }
            shape.setColor(
                if (cell === pressed) colors.active else when (cell.style) {
                    KeyStyle.NORMAL -> colors.key
                    KeyStyle.SPECIAL -> colors.special
                    KeyStyle.ACTIVE -> colors.active
                    KeyStyle.SPACE -> colors.key
                    KeyStyle.ENTER -> colors.enter
                    KeyStyle.TOOLBAR, KeyStyle.SUGGESTION -> colors.background
                }
            )
            shape.setBounds(
                cell.rect.left.toInt(), cell.rect.top.toInt(),
                cell.rect.right.toInt(), cell.rect.bottom.toInt()
            )
            shape.cornerRadius = if (cell.pill) cell.rect.height() / 2f else cornerRadius
            shape.draw(canvas)

            val ink = if (cell.style == KeyStyle.ENTER) colors.enterText else colors.text
            val glyph = cell.glyph
            if (cell.emojiFace) {
                drawEmojiKey(canvas, cell, colors)
            } else if (glyph != null) {
                drawKeyGlyph(canvas, cell, glyph, ink)
            } else {
                drawKeyText(canvas, cell.label, cell.style, cell.rect, ink)
            }

            val hint = cell.hint
            if (hint != null) {
                hintPaint.color = colors.hint
                hintPaint.textSize = dpf(10.5f)
                // Gboard puts the hint's baseline ~11dp below the key top, 4dp in from the right.
                canvas.drawText(hint, cell.rect.right - dp(4), cell.rect.top + dpf(11f), hintPaint)
            }
        }

        // Text sizes are in dp (not sp) so they match Gboard's fixed key text regardless of the
        // system font scale; 26dp = the 74px letters measured on a 2.8125-density screen.
        private fun drawKeyText(canvas: Canvas, label: String, style: KeyStyle, rect: RectF, ink: Int) {
            val isPageLabel = label == "?123" || label == "ABC"
            val sizeDp = when {
                style == KeyStyle.SPACE -> 13.5f
                isPageLabel -> 18.5f
                label == MORE_SYMBOLS -> 15.5f
                style == KeyStyle.ENTER -> 15f
                label.length > 1 -> 14f
                else -> 26f
            }
            textPaint.color = ink
            textPaint.typeface = if (isPageLabel || style == KeyStyle.ENTER) mediumFace else Typeface.DEFAULT
            textPaint.textSize = dpf(sizeDp)
            val metrics = textPaint.fontMetrics
            val baseline = rect.centerY() - (metrics.ascent + metrics.descent) / 2f
            canvas.drawText(label, rect.centerX(), baseline, textPaint)
            textPaint.typeface = Typeface.DEFAULT
        }

        // Icon sizes are Gboard's measured outlines (dp = px / 2.8125): shift 20.6x19.9,
        // delete 19.9x16.4, globe 19.6, enter 19.9x12.4. Stroke is inset so the outer edge matches.
        private fun drawKeyGlyph(canvas: Canvas, cell: Cell, glyph: KeyGlyph, ink: Int) {
            val cx = cell.rect.centerX()
            val cy = cell.rect.centerY()
            val sw = dpf(1.6f)
            iconStroke.color = ink
            iconStroke.strokeWidth = sw
            iconFill.color = ink
            when (glyph) {
                KeyGlyph.SHIFT, KeyGlyph.SHIFT_ON, KeyGlyph.CAPS -> {
                    val hw = (dpf(20.6f) - sw) / 2f
                    val hh = (dpf(19.9f) - sw) / 2f
                    val stemBottom = if (glyph == KeyGlyph.CAPS) hh * 0.6f else hh
                    val stem = hw * 0.45f
                    val arrow = Path().apply {
                        moveTo(cx, cy - hh)
                        lineTo(cx + hw, cy)
                        lineTo(cx + stem, cy)
                        lineTo(cx + stem, cy + stemBottom)
                        lineTo(cx - stem, cy + stemBottom)
                        lineTo(cx - stem, cy)
                        lineTo(cx - hw, cy)
                        close()
                    }
                    if (glyph != KeyGlyph.SHIFT) canvas.drawPath(arrow, iconFill)
                    canvas.drawPath(arrow, iconStroke)
                    if (glyph == KeyGlyph.CAPS) {
                        canvas.drawLine(cx - stem, cy + hh, cx + stem, cy + hh, iconStroke)
                    }
                }
                KeyGlyph.DELETE -> {
                    val hw = (dpf(19.9f) - sw) / 2f
                    val hh = (dpf(16.4f) - sw) / 2f
                    val tag = Path().apply {
                        moveTo(cx - hw, cy)
                        lineTo(cx - hw * 0.35f, cy - hh)
                        lineTo(cx + hw, cy - hh)
                        lineTo(cx + hw, cy + hh)
                        lineTo(cx - hw * 0.35f, cy + hh)
                        close()
                    }
                    canvas.drawPath(tag, iconStroke)
                    val xc = cx + hw * 0.33f
                    val d = hh * 0.38f
                    canvas.drawLine(xc - d, cy - d, xc + d, cy + d, iconStroke)
                    canvas.drawLine(xc - d, cy + d, xc + d, cy - d, iconStroke)
                }
                KeyGlyph.GLOBE -> {
                    val r = (dpf(19.6f) - sw) / 2f
                    canvas.drawCircle(cx, cy, r, iconStroke)
                    val rx = r * 0.45f
                    canvas.drawOval(cx - rx, cy - r, cx + rx, cy + r, iconStroke)
                    canvas.drawLine(cx - r, cy, cx + r, cy, iconStroke)
                    val lat = r * 0.5f
                    val half = r * 0.866f
                    canvas.drawLine(cx - half, cy - lat, cx + half, cy - lat, iconStroke)
                    canvas.drawLine(cx - half, cy + lat, cx + half, cy + lat, iconStroke)
                }
                KeyGlyph.ENTER -> {
                    val hw = (dpf(19.9f) - sw) / 2f
                    val hh = (dpf(12.4f) - sw) / 2f
                    val head = hh * 0.5f
                    val lineY = cy + hh - head
                    val tipX = cx - hw
                    val path = Path().apply {
                        moveTo(cx + hw, cy - hh)
                        lineTo(cx + hw, lineY)
                        lineTo(tipX, lineY)
                        moveTo(tipX + head, lineY - head)
                        lineTo(tipX, lineY)
                        lineTo(tipX + head, lineY + head)
                    }
                    canvas.drawPath(path, iconStroke)
                }
                KeyGlyph.NUMBER_PAD -> {
                    // Two stacked rows of digits, 60px tall as measured, centred on the key.
                    textPaint.color = ink
                    textPaint.typeface = Typeface.DEFAULT_BOLD
                    textPaint.textSize = dpf(11.8f)
                    canvas.drawText("1 2", cx, cy - dpf(2.3f), textPaint)
                    canvas.drawText("3 4", cx, cy + dpf(10.5f), textPaint)
                    textPaint.typeface = Typeface.DEFAULT
                }
            }
        }

        private fun drawSuggestion(canvas: Canvas, cell: Cell, colors: Palette) {
            if (cell === pressed) {
                shape.setColor(colors.special)
                shape.setBounds(
                    cell.rect.left.toInt(), (cell.rect.top + dpf(3f)).toInt(),
                    cell.rect.right.toInt(), (cell.rect.bottom - dpf(3f)).toInt()
                )
                shape.cornerRadius = cornerRadius
                shape.draw(canvas)
            }
            if (cell.rect.left > dpf(2f)) {
                iconStroke.color = colors.hint
                iconStroke.strokeWidth = dpf(1f)
                canvas.drawLine(
                    cell.rect.left, cell.rect.centerY() - dpf(10f),
                    cell.rect.left, cell.rect.centerY() + dpf(10f), iconStroke
                )
            }
            textPaint.color = colors.text
            textPaint.typeface = Typeface.DEFAULT
            val maxW = cell.rect.width() - dpf(16f)
            var size = dpf(17f)
            textPaint.textSize = size
            while (textPaint.measureText(cell.label) > maxW && size > dpf(12f)) {
                size -= dpf(1f)
                textPaint.textSize = size
            }
            var text = cell.label
            while (text.length > 1 && textPaint.measureText(text) > maxW) text = text.dropLast(1)
            val m = textPaint.fontMetrics
            canvas.drawText(text, cell.rect.centerX(), cell.rect.centerY() - (m.ascent + m.descent) / 2f, textPaint)
        }

        private fun drawEmojiKey(canvas: Canvas, cell: Cell, colors: Palette) {
            val cx = cell.rect.centerX()
            // Gboard's face is 28px (10dp) across, centred 13dp below the key top.
            val faceR = dpf(4.5f)
            val faceCy = cell.rect.top + dpf(13f)
            iconStroke.color = colors.text
            iconStroke.strokeWidth = dpf(1f)
            canvas.drawCircle(cx, faceCy, faceR, iconStroke)
            val eyeY = faceCy - faceR * 0.28f
            val eyeDx = faceR * 0.42f
            val eyeLen = faceR * 0.16f
            canvas.drawLine(cx - eyeDx, eyeY - eyeLen, cx - eyeDx, eyeY + eyeLen, iconStroke)
            canvas.drawLine(cx + eyeDx, eyeY - eyeLen, cx + eyeDx, eyeY + eyeLen, iconStroke)
            val smile = Path().apply {
                addArc(cx - faceR * 0.55f, faceCy - faceR * 0.1f,
                    cx + faceR * 0.55f, faceCy + faceR * 0.72f, 25f, 130f)
            }
            canvas.drawPath(smile, iconStroke)
            // The comma is ordinary key text, in the same place as on the symbol pages.
            drawKeyText(canvas, ",", KeyStyle.SPECIAL, cell.rect, colors.text)
        }

        private fun drawToolbarIcon(canvas: Canvas, cell: Cell, colors: Palette) {
            val color = if (cell === pressed) colors.active else colors.text
            val cx = cell.rect.centerX()
            val cy = cell.rect.centerY()
            val s = minOf(cell.rect.width(), cell.rect.height()) * 0.5f
            if (cell.icon == ToolIcon.SELECT) {
                iconFill.color = colors.special
                canvas.drawCircle(cx, cy, dpf(15.7f), iconFill)
            }
            iconStroke.color = color
            iconStroke.strokeWidth = (s * 0.16f).coerceAtLeast(1.6f * resources.displayMetrics.density)
            iconFill.color = color
            when (cell.icon) {
                ToolIcon.GRID -> {
                    val off = s * 0.52f
                    val side = s * 0.34f
                    for (dx in -1..1 step 2) for (dy in -1..1 step 2) {
                        val x = cx + dx * off
                        val y = cy + dy * off
                        canvas.drawRoundRect(x - side, y - side, x + side, y + side,
                            side * 0.6f, side * 0.6f, iconFill)
                    }
                }
                ToolIcon.UNDO -> {
                    val r = s * 0.62f
                    val arc = Path().apply { addArc(cx - r, cy - r * 0.55f, cx + r, cy + r * 0.9f, 200f, 140f) }
                    canvas.drawPath(arc, iconStroke)
                    val headX = cx - r * 0.94f
                    val headY = cy + r * 0.12f
                    canvas.drawLine(headX, headY, headX + s * 0.34f, headY - s * 0.2f, iconStroke)
                    canvas.drawLine(headX, headY, headX + s * 0.1f, headY + s * 0.36f, iconStroke)
                }
                ToolIcon.TRANSLATE -> {
                    val top = cy - s * 0.5f
                    canvas.drawLine(cx - s * 0.5f, cy + s * 0.1f, cx - s * 0.1f, top, iconStroke)
                    canvas.drawLine(cx - s * 0.1f, top, cx + s * 0.3f, cy + s * 0.1f, iconStroke)
                    canvas.drawLine(cx - s * 0.36f, cy - s * 0.12f, cx + s * 0.16f, cy - s * 0.12f, iconStroke)
                    canvas.drawLine(cx + s * 0.32f, cy - s * 0.5f, cx + s * 0.72f, cy - s * 0.5f, iconStroke)
                    canvas.drawLine(cx + s * 0.52f, cy - s * 0.5f, cx + s * 0.52f, cy + s * 0.1f, iconStroke)
                    canvas.drawLine(cx + s * 0.2f, cy + s * 0.1f, cx + s * 0.84f, cy + s * 0.55f, iconStroke)
                    canvas.drawLine(cx + s * 0.84f, cy + s * 0.1f, cx + s * 0.2f, cy + s * 0.55f, iconStroke)
                }
                ToolIcon.CLIPBOARD -> {
                    val l = cx - s * 0.52f
                    val r = cx + s * 0.52f
                    val t = cy - s * 0.62f
                    val b = cy + s * 0.62f
                    canvas.drawRoundRect(l, t, r, b, s * 0.2f, s * 0.2f, iconStroke)
                    canvas.drawRoundRect(cx - s * 0.24f, t - s * 0.22f, cx + s * 0.24f, t + s * 0.16f,
                        s * 0.12f, s * 0.12f, iconFill)
                    val lineL = l + s * 0.2f
                    val lineR = r - s * 0.2f
                    canvas.drawLine(lineL, cy - s * 0.1f, lineR, cy - s * 0.1f, iconStroke)
                    canvas.drawLine(lineL, cy + s * 0.2f, lineR, cy + s * 0.2f, iconStroke)
                }
                ToolIcon.SETTINGS -> {
                    val r = s * 0.42f
                    canvas.drawCircle(cx, cy, r, iconStroke)
                    canvas.drawCircle(cx, cy, r * 0.4f, iconStroke)
                    for (i in 0 until 8) {
                        val a = Math.toRadians(i * 45.0)
                        val dx = Math.cos(a).toFloat()
                        val dy = Math.sin(a).toFloat()
                        canvas.drawLine(cx + dx * r, cy + dy * r, cx + dx * s * 0.66f, cy + dy * s * 0.66f, iconStroke)
                    }
                }
                ToolIcon.SELECT -> {
                    canvas.drawLine(cx, cy - s * 0.6f, cx, cy + s * 0.6f, iconStroke)
                    canvas.drawLine(cx - s * 0.34f, cy - s * 0.6f, cx + s * 0.34f, cy - s * 0.6f, iconStroke)
                    canvas.drawLine(cx - s * 0.34f, cy + s * 0.6f, cx + s * 0.34f, cy + s * 0.6f, iconStroke)
                    canvas.drawLine(cx - s * 0.72f, cy, cx - s * 0.32f, cy, iconStroke)
                    canvas.drawLine(cx - s * 0.72f, cy, cx - s * 0.5f, cy - s * 0.2f, iconStroke)
                    canvas.drawLine(cx - s * 0.72f, cy, cx - s * 0.5f, cy + s * 0.2f, iconStroke)
                    canvas.drawLine(cx + s * 0.72f, cy, cx + s * 0.32f, cy, iconStroke)
                    canvas.drawLine(cx + s * 0.72f, cy, cx + s * 0.5f, cy - s * 0.2f, iconStroke)
                    canvas.drawLine(cx + s * 0.72f, cy, cx + s * 0.5f, cy + s * 0.2f, iconStroke)
                }
                null -> {}
            }
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    val cell = cellAt(event.x, event.y)
                    pressed = cell
                    longPressFired = false
                    invalidate()
                    if (cell != null) {
                        keyFeedback(this)
                        if (cell.isDelete) {
                            beginDelete()
                        } else {
                            cell.longAction?.let { action ->
                                val runnable = Runnable {
                                    longPressFired = true
                                    action()
                                }
                                pendingLongPress = runnable
                                pressHandler.postDelayed(runnable, 400L)
                            }
                        }
                    }
                    return true
                }
                MotionEvent.ACTION_MOVE -> {
                    val cell = cellAt(event.x, event.y)
                    if (cell !== pressed) {
                        pressed = cell
                        cancelPendingLongPress()
                        stopDeleteRepeat()
                        invalidate()
                    }
                    return true
                }
                MotionEvent.ACTION_UP -> {
                    cancelPendingLongPress()
                    val wasRepeating = deleteRepeat != null
                    stopDeleteRepeat()
                    val cell = pressed
                    pressed = null
                    invalidate()
                    if (cell != null && !longPressFired && !cell.isDelete &&
                        cellAt(event.x, event.y) === cell) {
                        cell.action()
                    }
                    if (wasRepeating) post { afterTextChanged() }
                    longPressFired = false
                    return true
                }
                MotionEvent.ACTION_CANCEL -> {
                    cancelPendingLongPress()
                    stopDeleteRepeat()
                    pressed = null
                    longPressFired = false
                    invalidate()
                    return true
                }
            }
            return super.onTouchEvent(event)
        }

        private fun cellAt(x: Float, y: Float): Cell? {
            for (cell in cells) {
                if (cell.touchRect.contains(x, y)) return cell
            }
            return null
        }

        // Press deletes once for a tap; a hold waits out an initial delay then repeats,
        // speeding up as the hold continues.
        private fun beginDelete() {
            deleteOne(updateShift = false)
            deleteSpeedMs = DELETE_START_MS
            val hold = Runnable { startDeleteRepeat() }
            deleteHold = hold
            deleteHandler.postDelayed(hold, DELETE_INITIAL_DELAY_MS)
        }

        private fun startDeleteRepeat() {
            deleteHold = null
            val repeat = object : Runnable {
                override fun run() {
                    deleteOne(updateShift = false)
                    deleteSpeedMs = (deleteSpeedMs - DELETE_ACCEL_STEP).coerceAtLeast(DELETE_MIN_MS)
                    deleteRepeat = this
                    deleteHandler.postDelayed(this, deleteSpeedMs.toLong())
                }
            }
            repeat.run()
        }

        private fun cancelPendingLongPress() {
            pendingLongPress?.let { pressHandler.removeCallbacks(it) }
            pendingLongPress = null
        }

        // TalkBack sees each drawn key as a virtual button child of this view.
        override fun getAccessibilityNodeProvider(): AccessibilityNodeProvider =
            KeyboardAccessibilityProvider()
    }

    private inner class KeyboardAccessibilityProvider : AccessibilityNodeProvider() {
        override fun createAccessibilityNodeInfo(virtualViewId: Int): AccessibilityNodeInfo? {
            val view = keyboardView ?: return null
            if (virtualViewId == AccessibilityNodeProvider.HOST_VIEW_ID) {
                val host = AccessibilityNodeInfo.obtain(view)
                view.cells.indices.forEach { host.addChild(view, it) }
                return host
            }
            val cells = view.cells
            if (virtualViewId < 0 || virtualViewId >= cells.size) return null
            val cell = cells[virtualViewId]
            val info = AccessibilityNodeInfo.obtain(view, virtualViewId)
            info.className = "android.widget.Button"
            info.contentDescription = cell.contentDescription
            info.text = cell.label
            info.isClickable = true
            info.setBoundsInParent(
                Rect(
                    cell.rect.left.toInt(), cell.rect.top.toInt(),
                    cell.rect.right.toInt(), cell.rect.bottom.toInt()
                )
            )
            return info
        }

        override fun performAction(
            virtualViewId: Int,
            action: Int,
            arguments: Bundle?
        ): Boolean {
            val view = keyboardView ?: return false
            if (virtualViewId in view.cells.indices && action == AccessibilityNodeInfo.ACTION_CLICK) {
                view.cells[virtualViewId].action()
                return true
            }
            return super.performAction(virtualViewId, action, arguments)
        }
    }

    companion object {
        private const val GLOBE = "\uD83C\uDF10"
        private const val DELETE = "\u232B"
        private const val ENTER = "\u21B5"
        private const val SHIFT = "\u2191"
        private const val SHIFT_ON = "\u21E7"
        private const val CAPS = "\u2EA6"
        private const val MORE_SYMBOLS = "=\\<"
        private const val NUMBER_PAD = "12/34"

        // Gboard reference geometry (1080px-wide screen): the key block is 678px tall with a
        // 124px bottom row, and each key is inset by ~6.7% of its row's unit width per side.
        private const val REF_BLOCK_H = 678f
        private const val REF_BOTTOM_ROW_H = 124f
        private const val KEY_INSET = 0.067f

        // Top strip (tool icons / suggestions): 40dp band, keys start at 49dp, so the view is
        // 45dp taller than the configured key-area height.
        private const val STRIP_BAND_DP = 40f
        private const val STRIP_TOP_DP = 49f
        private const val STRIP_EXTRA_DP = 45

        // Thai characters a word cannot start with (vowel and tone marks, ะ า ำ ๅ ๆ ฯ).
        private const val THAI_NON_LEADING = "ะาำัิีึืุู็่้๊๋์ํๅๆฯฺ"

        private val SETTINGS_KEYS = setOf(
            "keyboard_height_dp", "theme", "number_row_en", "number_row_th", "key_hints", "suggestions"
        )

        private const val DELETE_INITIAL_DELAY_MS = 400L
        private const val DELETE_START_MS = 60
        private const val DELETE_ACCEL_STEP = 3
        private const val DELETE_MIN_MS = 15
    }
}
