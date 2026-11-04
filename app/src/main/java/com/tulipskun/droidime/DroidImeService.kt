package com.tulipskun.droidime

import android.content.Context
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
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

    private enum class KeyStyle { NORMAL, SPECIAL, ACTIVE, SPACE, ENTER }

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
    private val darkPalette = Palette(
        background = Color.rgb(24, 25, 28),
        key = Color.rgb(62, 64, 70),
        special = Color.rgb(44, 46, 51),
        active = Color.rgb(86, 104, 150),
        enter = Color.rgb(96, 130, 214),
        text = Color.rgb(234, 236, 241),
        enterText = Color.WHITE,
        hint = Color.rgb(160, 166, 178)
    )

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
        val height = preferences.getInt("keyboard_height_dp", 280).coerceIn(220, 360)
        val target = view ?: return
        val params = target.layoutParams ?: ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            dp(height)
        )
        params.height = dp(height)
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
        keyboardView?.rebuild()
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
        if (!preferences.getBoolean("auto_caps", true)) return false
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

    private fun sp(value: Float): Float = value * resources.displayMetrics.scaledDensity

    // A single drawn key. The rect is filled in during layout; null spacer slots have no cell.
    private class Cell(
        val label: String,
        val style: KeyStyle,
        val hint: String?,
        val action: () -> Unit,
        val longAction: (() -> Unit)? = null,
        val isDelete: Boolean = false,
        val rect: RectF = RectF()
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
            textAlign = Paint.Align.LEFT
            typeface = Typeface.DEFAULT
        }
        private val shape = GradientDrawable()
        private val cornerRadius = dp(7).toFloat()

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
            val desired = dp(preferences.getInt("keyboard_height_dp", 280).coerceIn(220, 360))
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
                val specialWidth = if (row.size == 7) 1.5f else 1.25f
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
            return Cell(label, style, null, action = { onShiftTap() })
        }

        private fun pageKeyCell(): Cell {
            val label = if (symbolPage == 0) MORE_SYMBOLS else "?123"
            return Cell(label, KeyStyle.SPECIAL, null, action = {
                symbolPage = 1 - symbolPage
                renderKeyboard()
            })
        }

        private fun deleteCell(): Cell =
            Cell(DELETE, KeyStyle.SPECIAL, null, isDelete = true, action = { deleteOne() })

        private fun bottomRow(): List<Pair<Float, Cell?>> {
            val slots = mutableListOf<Pair<Float, Cell?>>()
            if (numberPad) {
                slots.add(1.25f to Cell("?123", KeyStyle.SPECIAL, null, action = {
                    numberPad = false
                    symbols = true
                    symbolPage = 0
                    renderKeyboard()
                }))
                slots.add(1.25f to Cell("ABC", KeyStyle.SPECIAL, null, action = {
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
                val spaceLabel = when {
                    symbols -> "space"
                    language == Language.THAI -> "ไทย"
                    else -> "English"
                }
                slots.add(1.3f to Cell(if (symbols) "ABC" else "?123", KeyStyle.SPECIAL, null, action = {
                    symbols = !symbols
                    symbolPage = 0
                    if (!shiftLocked) shifted = computeAutoShift()
                    renderKeyboard()
                }))
                slots.add(0.8f to Cell(leftSide, KeyStyle.NORMAL, null, action = { typeText(leftSide) }))
                if (symbols) {
                    slots.add(1f to Cell(NUMBER_PAD, KeyStyle.SPECIAL, null, action = {
                        symbols = false
                        numberPad = true
                        renderKeyboard()
                    }))
                } else {
                    slots.add(1f to Cell(GLOBE, KeyStyle.SPECIAL, null,
                        longAction = { showKeyboardPicker() },
                        action = { switchLanguage() }))
                }
                slots.add(4.3f to Cell(spaceLabel, KeyStyle.SPACE, null, action = { typeSpace() }))
                slots.add(0.8f to Cell(rightSide, KeyStyle.NORMAL, null, action = { typeText(rightSide) }))
                slots.add(1.5f to enterCell())
            }
            return slots
        }

        private fun enterCell(): Cell =
            Cell(enterLabel(), KeyStyle.ENTER, null, action = { sendEnter() })

        private fun layoutRows(rows: List<List<Pair<Float, Cell?>>>) {
            val flat = mutableListOf<Cell>()
            if (rows.isEmpty()) {
                cells = flat
                return
            }
            val padX = dp(3)
            val padTop = dp(8)
            val padBottom = dp(8) + navigationInset
            val gap = dp(4)
            val keyGap = dp(2)
            val availW = (width - padX * 2).toFloat()
            val availH = (height - padTop - padBottom).toFloat()
            val rowH = ((availH - gap * (rows.size - 1)) / rows.size).coerceAtLeast(1f)
            rows.forEachIndexed { index, row ->
                val totalWeight = row.sumOf { it.first.toDouble() }.toFloat()
                if (totalWeight <= 0f) return@forEachIndexed
                val unit = availW / totalWeight
                var x = padX.toFloat()
                val top = padTop + index * (rowH + gap)
                val bottom = top + rowH
                row.forEach { (weight, cell) ->
                    val slotRight = x + weight * unit
                    if (cell != null) {
                        cell.rect.set(x + keyGap, top, slotRight - keyGap, bottom)
                        flat.add(cell)
                    }
                    x = slotRight
                }
            }
            cells = flat
        }

        private fun drawCell(canvas: Canvas, cell: Cell) {
            val colors = palette
            shape.setColor(
                if (cell === pressed) colors.active else when (cell.style) {
                    KeyStyle.NORMAL -> colors.key
                    KeyStyle.SPECIAL -> colors.special
                    KeyStyle.ACTIVE -> colors.active
                    KeyStyle.SPACE -> colors.key
                    KeyStyle.ENTER -> colors.enter
                }
            )
            shape.setBounds(
                cell.rect.left.toInt(), cell.rect.top.toInt(),
                cell.rect.right.toInt(), cell.rect.bottom.toInt()
            )
            shape.cornerRadius = cornerRadius
            shape.draw(canvas)

            textPaint.color = if (cell.style == KeyStyle.ENTER) colors.enterText else colors.text
            textPaint.textSize = sp(
                when {
                    cell.style == KeyStyle.SPACE -> 12f
                    cell.label.length > 4 -> 11f
                    cell.label.length > 1 -> 14f
                    else -> 20f
                }
            )
            val metrics = textPaint.fontMetrics
            val baseline = cell.rect.centerY() - (metrics.ascent + metrics.descent) / 2f
            canvas.drawText(cell.label, cell.rect.centerX(), baseline, textPaint)

            val hint = cell.hint
            if (hint != null) {
                hintPaint.color = colors.hint
                hintPaint.textSize = sp(10f)
                val hm = hintPaint.fontMetrics
                canvas.drawText(
                    hint,
                    cell.rect.left + dp(4),
                    cell.rect.top + dp(2) - hm.ascent,
                    hintPaint
                )
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
                            startDeleteRepeat()
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
                    if (cell != null && !longPressFired && cellAt(event.x, event.y) === cell) {
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
                if (cell.rect.contains(x, y)) return cell
            }
            return null
        }

        private fun startDeleteRepeat() {
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
    }
}
