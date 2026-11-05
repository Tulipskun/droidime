package com.tulipskun.droidime

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.ClipData
import android.content.ClipboardManager
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
import android.view.inputmethod.ExtractedTextRequest
import android.view.inputmethod.InputMethodManager
import org.json.JSONObject

class DroidImeService : InputMethodService() {
    private enum class Language(val code: String) {
        THAI("th"),
        ENGLISH("en")
    }

    private enum class FieldKind { TEXT, EMAIL, URL, PASSWORD, NUMBER, PHONE }

    private enum class KeyStyle {
        NORMAL, SPECIAL, ACTIVE, SPACE, ENTER, TOOLBAR, SUGGESTION, CLIP, HEADER, HEADER_CLOSE, SECTION, EMOJI, EMOJI_CATEGORY
    }
    private enum class EmojiCategory(val title: String, val icon: String, val items: List<String>) {
        FACES("Faces", "😀", listOf("😀","😃","😄","😁","😆","😅","😂","🤣","😊","😉","😍","🥰","😘","😋","😜","🤪","🤔","😎","🥳","🥺","😭","😱","🤖","👻","💩","🎃")),
        PEOPLE("People", "👤", listOf("👋","👌","✌️","🤞","🤟","🤘","👍","👎","👏","🙌","🙏","💪","👀","👄","👶","🧒","👦","👧","🧑","👨","👩","🤦","🤷","🧑‍💻")),
        NATURE("Animals & nature", "🐾", listOf("🐶","🐱","🐭","🐹","🐰","🦊","🐻","🐼","🐨","🐯","🦁","🐮","🐷","🐸","🐵","🐔","🐧","🦋","🐝","🐢","🐍","🦖","🐙","🦀","🐠","🐬","🌸","🌹","🌻","🌈","☀️","🌙")),
        FOOD("Food & drink", "🍎", listOf("🍏","🍎","🍊","🍋","🍌","🍉","🍇","🍓","🫐","🍒","🍑","🥭","🍍","🥝","🍅","🥑","🥦","🥕","🍞","🧀","🍳","🍔","🍟","🍕","🌮","🍜","🍣","🍱","🍦","🍰","🎂","🍩","☕","🧋")),
        ACTIVITIES("Activities", "⚽", listOf("⚽","🏀","🏈","⚾","🥎","🎾","🏐","🏉","🥏","🎱","🏓","🏸","🥅","⛳","🏹","🎣","🤿","🥊","🥋","🎽","🛹","🛼","🛷","⛸️","🎿","🏆","🥇","🎮","🕹️","🎲","🧩","🎯","🎨","🎭","🎤","🎧","🎼","🎹","🥁")),
        TRAVEL("Travel & places", "🚗", listOf("🚗","🚕","🚌","🏎️","🚑","🚒","🚲","🛴","🚦","✈️","🚀","🛸","🌍","🗺️","🏔️","🏕️","🏖️","🏠","🏢","🏰","🗼","🗽","🚆","🚇","🚢","⛵","🚁","🌋","🏝️","🌃")),
        OBJECTS("Objects", "💡", listOf("💡","🔦","🕯️","🪔","📱","💻","⌨️","🖥️","🖨️","🖱️","🕹️","💽","💾","📷","📹","🎥","📺","📻","⏰","⌚","🔋","🔌","💰","💎","🔧","🔨","⚙️","🧲","💊","🩹","📚","✏️","🖊️","✂️","🔒","🔑")),
        SYMBOLS("Symbols", "♥", listOf("❤️","🧡","💛","💚","💙","💜","🖤","🤍","💔","💕","💞","💓","💗","💖","💘","💝","☮️","✝️","☪️","🕉️","☯️","❌","⭕","💯","❗","❓","⚠️","♻️","✅","➡️","⬅️","⬆️","⬇️")),
        FLAGS("Flags", "🏳️", listOf("🏳️","🏴","🏁","🚩","🏳️‍🌈","🏳️‍⚧️","🇹🇭","🇺🇸","🇬🇧","🇯🇵","🇰🇷","🇨🇳","🇮🇳","🇫🇷","🇩🇪","🇮🇹","🇪🇸","🇧🇷","🇨🇦","🇦🇺","🇳🇿","🇸🇬","🇲🇾","🇮🇩","🇻🇳","🇵🇭","🇱🇦","🇰🇭","🇲🇲"))
    }

    private enum class ToolIcon { GRID, UNDO, TRANSLATE, CLIPBOARD, SETTINGS, SELECT }

    // Vector icons drawn on keys instead of font glyphs, so they look the same on every device
    // (the globe emoji, for one, renders as a coloured picture on Android).
    private enum class KeyGlyph { SHIFT, SHIFT_ON, CAPS, DELETE, ENTER, GLOBE, NUMBER_PAD }

    // One tappable word suggestion: replace the last `replaceLen` characters with `text + append`.
    // `score` is the ranking signal used to order the strip (higher is better).
    private data class Suggestion(
        val text: String,
        val replaceLen: Int,
        val append: String,
        val score: Int = 0
    )

    // Words sorted for binary-search prefix lookup, with a frequency per word.
    private class Dictionary(val words: Array<String>, val freqs: IntArray) {
        // Maps a word with all Thai vowel/tone marks removed to the index of the most frequent
        // wording that builds it. Thai typing often drops marks, so "สวสด" is found by looking up
        // the mark-free key of "สวัสดี".
        val strippedIndex: HashMap<String, Int>

        init {
            val index = HashMap<String, Int>()
            for (i in words.indices) {
                val key = words[i].filterNot { it in THAI_MARKS }
                val current = index[key]
                if (current == null || freqs[i] > freqs[current]) index[key] = i
            }
            strippedIndex = index
        }

        private fun lowerBound(prefix: String): Int {
            var lo = 0
            var hi = words.size
            while (lo < hi) {
                val mid = (lo + hi) ushr 1
                if (words[mid] < prefix) lo = mid + 1 else hi = mid
            }
            return lo
        }

        fun indexOf(word: String): Int {
            val i = lowerBound(word)
            return if (i < words.size && words[i] == word) i else -1
        }

        // Dictionary words one edit away from `word` (a letter dropped, swapped, replaced or added),
        // which is how "teh" offers "the".
        fun corrections(word: String): List<Int> {
            val found = LinkedHashSet<Int>()
            fun probe(candidate: String) {
                if (candidate == word) return
                val i = indexOf(candidate)
                if (i >= 0) found.add(i)
            }
            for (i in word.indices) probe(word.substring(0, i) + word.substring(i + 1))
            for (i in 0 until word.length - 1) {
                probe(word.substring(0, i) + word[i + 1] + word[i] + word.substring(i + 2))
            }
            for (i in word.indices) {
                for (c in 'a'..'z') probe(word.substring(0, i) + c + word.substring(i + 1))
            }
            for (i in 0..word.length) {
                for (c in 'a'..'z') probe(word.substring(0, i) + c + word.substring(i))
            }
            return found.toList()
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
    // After a glide word is committed, the strip offers the runner-up words until the text changes.
    private var glideCommitted: String? = null
    private var glideAlternatives: List<Suggestion> = emptyList()
    @Volatile private var dictEn: Dictionary? = null
    @Volatile private var dictTh: Dictionary? = null

    // Words the user has typed that aren't in the shipped dictionary. Insertion ordered, with a
    // usage count per word; kept in the settings file so they survive restarts and feed back into
    // suggestions and glide matching. Growth is deliberate (an explicit word beats a wrong one), so
    // a single disposition check is enough; this stays on the main thread for simplicity.
    private val userWords = linkedMapOf<String, Int>()

    // ---- Clipboard -----------------------------------------------------------------------------
    //
    // Gboard keeps the copies made while it is the keyboard and shows them in a clipboard panel.
    // The system clipboard only ever holds the newest clip, so the panel is backed by our own
    // list. A clip is only recorded when the keyboard itself put it there (a copy the user made
    // with the select-all/copy tool) or when the system clipboard changed while this keyboard
    // was showing — a copy made in another app is visible to the keyboard, but only while it is
    // on screen, so that is the only moment it can be picked up.
    private val clips = ArrayList<Clip>()
    private var clipMenuId: String? = null
    private var clipSelection: String? = null
    private var clipEdit = false

    private class Clip(val id: String, val text: String, val pinned: Boolean, val at: Long) {
        fun copy(pinned: Boolean = this.pinned, at: Long = this.at) = Clip(id, text, pinned, at)
    }

    // A clip's text with the characters a reader should not have to scroll through collapsed.
    private fun clipPreview(text: String): String =
        text.replace(Regex("\\s+"), " ").trim().let { if (it.length > 80) it.take(80) + "…" else it }

    private fun clipId(text: String): String = text.trim()

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
    private var spaceDragStartX = 0f
    private var spaceDragStartCursor = -1
    private var spaceDragLastCursor = -1
    private var lastTouchEventAt = 0L

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
        loadUserWords()
        loadClips()
        val clipboard = clipboardManager()
        if (clipboard != null) {
            clipboard.addPrimaryClipChangedListener(clipListener)
            // The keyboard may be started with a clip already on the clipboard; pick it up so the
            // panel is not empty on the first open.
            recordSystemClip(clipboard)
        }
        Thread {
            dictEn = loadDictionary("words_en.txt")
            dictTh = loadDictionary("words_th.txt")
            settingsHandler.post { updateSuggestions() }
        }.start()
    }

    override fun onDestroy() {
        preferences.unregisterOnSharedPreferenceChangeListener(prefListener)
        stopDeleteRepeat()
        clipboardManager()?.removePrimaryClipChangedListener(clipListener)
        keyboardView = null
        super.onDestroy()
    }

    private fun clipboardManager(): ClipboardManager? =
        getSystemService(CLIPBOARD_SERVICE) as? ClipboardManager

    // The keyboard is allowed to read the clipboard while it is the input method, so a copy made
    // anywhere while it is showing arrives here. Listener callbacks come on the main thread.
    private val clipListener = ClipboardManager.OnPrimaryClipChangedListener {
        val manager = clipboardManager() ?: return@OnPrimaryClipChangedListener
        recordSystemClip(manager)
    }

    private fun recordSystemClip(manager: ClipboardManager) {
        val clip = try {
            manager.primaryClip
        } catch (_: Exception) {
            null
        } ?: return
        val text = clipText(clip) ?: return
        addClip(text, pinned = false, system = true)
    }

    private fun clipText(clip: ClipData): String? {
        if (clip.itemCount == 0) return null
        for (i in 0 until clip.itemCount) {
            val text = clip.getItemAt(i).coerceToText(this)?.toString()
            if (!text.isNullOrEmpty()) return text
        }
        return null
    }

    // ---- Clipboard storage ---------------------------------------------------------------------

    private fun addClip(text: String, pinned: Boolean, system: Boolean) {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return
        val id = clipId(trimmed)
        val existing = clips.indexOfFirst { it.id == id }
        if (existing >= 0) {
            val old = clips.removeAt(existing)
            clips.add(0, old.copy(pinned = old.pinned || pinned, at = SystemClock.elapsedRealtime()))
            saveClips()
            return
        }
        clips.add(0, Clip(id, trimmed, pinned, SystemClock.elapsedRealtime()))
        // Gboard keeps roughly the last handful of copies; anything older is dropped, pinned or not.
        while (clips.size > CLIP_KEEP) {
            val drop = clips.indexOfLast { !it.pinned }
            if (drop < 0) break
            clips.removeAt(drop)
        }
        saveClips()
        // A copy that arrives while the panel is closed should not silently become a suggestion.
        if (system) clipSelection = null
    }

    private fun saveClips() {
        val sb = StringBuilder()
        for (clip in clips) {
            if (sb.length + clip.text.length > CLIP_STORE_LIMIT) break
            sb.append(if (clip.pinned) "1\t" else "0\t")
                .append(clip.text.replace("\n", "\\n")).append('\n')
        }
        preferences.edit().putString(CLIP_DICT, sb.toString()).apply()
    }

    private fun loadClips() {
        clips.clear()
        val raw = preferences.getString(CLIP_DICT, null) ?: return
        for (line in raw.lines()) {
            if (line.isEmpty()) continue
            val pinned = line.startsWith("1\t")
            val text = (if (pinned) line.substring(2) else line.substring(2)).replace("\\n", "\n")
            if (text.trim().isEmpty()) continue
            clips.add(Clip(clipId(text), text, pinned, 0L))
        }
    }

    private fun removeClip(id: String) {
        val index = clips.indexOfFirst { it.id == id }
        if (index < 0) return
        clips.removeAt(index)
        saveClips()
    }

    private fun toggleClipPin(id: String) {
        val index = clips.indexOfFirst { it.id == id }
        if (index < 0) return
        clips[index] = clips[index].copy(pinned = !clips[index].pinned)
        saveClips()
    }

    // The panel shows pinned clips first, newest pinned above older ones, then recent copies.
    private fun clipsForPanel(): List<Clip> =
        clips.sortedWith(compareByDescending<Clip> { it.pinned }.thenByDescending { it.at })

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
        // The panel belongs to one editing session: leaving the field closes it.
        clipSelection = null
        clipMenuId = null
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
        // Safe time to capture a trailing Thai or English word that was typed without ever being
        // followed by a space.
        learnTrailingWord()
        super.onFinishInputView(finishingInput)
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

    // ---- Suggestions ---------------------------------------------------------------------------
    // Suggestions follow the characters before the cursor, whichever keyboard language is shown.
    // Pressing a key while the panel is open acts on the clip, so the strip is not involved.
    private fun computeSuggestions(): List<Suggestion> {
        if (!preferences.getBoolean("suggestions", true)) return emptyList()
        if (panelOpen()) return emptyList()
        if (symbols || numberPad || fieldKind != FieldKind.TEXT) return emptyList()
        val type = currentInputEditorInfo?.inputType ?: return emptyList()
        if ((type and InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS) != 0) return emptyList()
        val connection = currentInputConnection ?: return emptyList()
        glideCommitted?.let { committed ->
            val tail = connection.getTextBeforeCursor(committed.length, 0)?.toString()
            if (tail == committed) return glideAlternatives
            glideCommitted = null
        }
        val after = connection.getTextAfterCursor(1, 0)
        if (!after.isNullOrEmpty() && after[0].isLetterOrDigit()) return emptyList()
        val before = connection.getTextBeforeCursor(24, 0)?.toString() ?: return emptyList()
        if (before.isEmpty()) return emptyList()
        return if (isThai(before.last())) thaiSuggestions(before) else englishSuggestions(before)
    }

    private fun englishSuggestions(before: String): List<Suggestion> {
        val dict = dictEn
        var start = before.length
        while (start > 0 && (before[start - 1] in 'a'..'z' || before[start - 1] in 'A'..'Z' || before[start - 1] == '\'')) start--
        val typed = before.substring(start)
        if (typed.isEmpty() || typed.length > 20) return emptyList()
        val lower = typed.lowercase()
        // Completions and one-edit corrections compete on frequency, like Gboard's strip where
        // "test" offers both "rest" and "tested" next to the word as typed. Words from the shipped
        // dictionary and the user's own vocabulary are scored together by word.
        val scores = LinkedHashMap<String, Int>()
        if (dict != null) {
            for (index in dict.complete(lower, 8)) {
                val word = dict.words[index]
                val score = dict.freqs[index] - (word.length - lower.length)
                val old = scores[word]
                if (old == null || score > old) scores[word] = score
            }
            if (lower.length >= 3) {
                for (index in dict.corrections(lower)) {
                    val word = dict.words[index]
                    val score = dict.freqs[index] - EDIT_PENALTY
                    val old = scores[word]
                    if (old == null || score > old) scores[word] = score
                }
            }
        }
        // Personal dictionary: prefix completions and one-edit corrections, scored by how often the
        // user has typed them (a practice the base frequencies can't see).
        for ((word, count) in userWords) {
            if (word == lower) continue
            if (word.length > lower.length && word.startsWith(lower)) {
                val score = count * 3 - (word.length - lower.length)
                val old = scores[word]
                if (old == null || score > old) scores[word] = score
            } else if (lower.length >= 3 && oneEdit(word, lower)) {
                val score = count * 3 - EDIT_PENALTY
                val old = scores[word]
                if (old == null || score > old) scores[word] = score
            }
        }
        if (scores.isEmpty()) return emptyList()
        val take = if (typed.length >= 2) 2 else 3
        val found = scores.entries.sortedByDescending { entry -> entry.value }
            .take(take).map { entry -> entry.key }
        val allCaps = typed.length > 1 && typed.all { it.isUpperCase() }
        val capital = typed[0].isUpperCase()
        val out = ArrayList<Suggestion>()
        // The word as typed stays available as the first choice, like Gboard.
        if (typed.length >= 2) out.add(Suggestion(typed, typed.length, " "))
        for (word in found) {
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
    // shorter endings (down to a single consonant, so suggestions start with the first
    // character typed). The word as typed takes the first slot, like Gboard.
    private fun thaiSuggestions(before: String): List<Suggestion> {
        val dict = dictTh
        var start = before.length
        while (start > 0 && isThai(before[start - 1]) && before.length - start < 12) start--
        val run = before.substring(start)
        // Completions are ranked by frequency, with a small bonus for the word as typed and for
        // words the user has already used, so the strip's order is stable as more is typed.
        val completions = ArrayList<Suggestion>()
        var typedLen = 0
        for (k in run.length downTo 1) {
            val suffix = run.substring(run.length - k)
            if (suffix[0] in THAI_NON_LEADING) continue
            if (typedLen == 0 && dict != null && dict.indexOf(suffix) >= 0) typedLen = k
            if (dict != null) {
                for (index in dict.complete(suffix, 3)) {
                    val word = dict.words[index]
                    if (completions.none { s -> s.text == word }) {
                        completions.add(Suggestion(word, k, "", dict.freqs[index]))
                    }
                }
            }
            // Learned vocabulary completes alongside the shipped words.
            if (completions.size < 3) {
                for ((word, count) in userWords) {
                    if (word.length > suffix.length && word.startsWith(suffix) &&
                        completions.none { s -> s.text == word }) {
                        completions.add(Suggestion(word, k, "", count * 4))
                    }
                }
            }
            if (completions.size >= 2 && typedLen > 0) break
        }

        // Typed fast, marks dropped: "สวสด" is "สวัสดี" with its vowel/tone marks left out. When
        // nothing matches as a prefix, look up the mark-free spelling in the dictionary and in the
        // user's vocabulary, and fall back to a single-edit fix of the same mark-free form.
        if (typedLen == 0 && completions.isEmpty() && run.length >= 2) {
            val stripped = run.filterNot { it in THAI_MARKS }
            val fixes = ArrayList<Suggestion>()
            dict?.strippedIndex?.get(stripped)?.let { idx ->
                fixes.add(Suggestion(dict.words[idx], run.length, "", dict.freqs[idx]))
            }
            for ((word, _) in userWords) {
                if (word == run) continue
                val w = word.filterNot { it in THAI_MARKS }
                if (w == stripped || oneEdit(w, stripped)) {
                    if (fixes.none { s -> s.text == word }) fixes.add(Suggestion(word, run.length, ""))
                }
            }
            completions.addAll(fixes.take(3))
        }

        if (typedLen == 0 && completions.isNotEmpty()) typedLen = completions[0].replaceLen
        if (typedLen == 0) return emptyList()
        val out = ArrayList<Suggestion>()
        out.add(Suggestion(run.substring(run.length - typedLen), typedLen, ""))
        for (c in completions.sortedByDescending { it.score }) {
            if (out.size == 3) break
            out.add(c)
        }
        return out
    }

    // ---- Glide typing -------------------------------------------------------------------------

    private fun clipOpen(): Boolean = preferences.getBoolean("clipboard", true) && clipPanelVisible
    private fun panelOpen(): Boolean = clipOpen() || emojiPanelVisible

    // The panel replaces the suggestion strip and the letter rows, so the keys that are drawn
    // while it is open are not glide letters.
    private var clipPanelVisible = false
    private var emojiPanelVisible = false
    private var emojiCategory = EmojiCategory.FACES

    private fun glideAllowed(): Boolean =
        !panelOpen() && !symbols && !numberPad && fieldKind == FieldKind.TEXT &&
            ((if (language == Language.THAI) dictTh else dictEn) != null || userWords.isNotEmpty()) &&
            preferences.getBoolean("glide_typing", true)

    private fun hypotf(dx: Float, dy: Float): Float = Math.sqrt((dx * dx + dy * dy).toDouble()).toFloat()

    private fun pathLength(xs: FloatArray, ys: FloatArray): Float {
        var total = 0f
        for (i in 1 until xs.size) total += hypotf(xs[i] - xs[i - 1], ys[i] - ys[i - 1])
        return total
    }

    // `n` points spaced evenly along the polyline, as x0,y0,x1,y1,...
    private fun resample(xs: FloatArray, ys: FloatArray, n: Int): FloatArray {
        val out = FloatArray(n * 2)
        val total = pathLength(xs, ys)
        if (xs.size < 2 || total <= 0f) {
            for (k in 0 until n) {
                out[2 * k] = xs[0]
                out[2 * k + 1] = ys[0]
            }
            return out
        }
        val step = total / (n - 1)
        var seg = 1
        var segStart = 0f
        var segLen = hypotf(xs[1] - xs[0], ys[1] - ys[0])
        for (k in 0 until n) {
            val target = step * k
            while (seg < xs.size - 1 && segStart + segLen < target) {
                segStart += segLen
                seg++
                segLen = hypotf(xs[seg] - xs[seg - 1], ys[seg] - ys[seg - 1])
            }
            val t = if (segLen > 0f) ((target - segStart) / segLen).coerceIn(0f, 1f) else 0f
            out[2 * k] = xs[seg - 1] + (xs[seg] - xs[seg - 1]) * t
            out[2 * k + 1] = ys[seg - 1] + (ys[seg] - ys[seg - 1]) * t
        }
        return out
    }

    // Words whose key-to-key path best matches the finger's path: only words that start and end
    // near where the gesture did are tried, each path is resampled to the same number of points
    // and compared, and word frequency breaks near-ties. Thai words are also tried without their
    // vowel and tone marks, since those are easy to skip over while gliding. Returns up to three
    // words, best first.
    private fun glideCandidates(
        xs: FloatArray, ys: FloatArray, centers: Map<Char, FloatArray>, keyW: Float, thai: Boolean
    ): List<String> {
        val dict = (if (thai) dictTh else dictEn)
        if (xs.size < 2 || centers.isEmpty() || keyW <= 0f) return emptyList()
        if (dict == null && userWords.isEmpty()) return emptyList()
        val n = 32
        val gesture = resample(xs, ys, n)
        val gestureLen = pathLength(xs, ys)
        // A fat finger can land well outside the intended key. Accepting any key within 2.2 key
        // widths of the touch (plus the next ring up to ~2.9 widths for a near-miss release) lets
        // a glide tolerate sliding a little off the key path without inventing words far away.
        fun near(x: Float, y: Float): Set<Char> {
            val keys = HashSet<Char>()
            for ((letter, c) in centers) if (hypotf(c[0] - x, c[1] - y) <= keyW * 2.2f) keys.add(letter)
            return keys
        }
        val firstKeys = near(xs.first(), ys.first())
        val lastKeys = near(xs.last(), ys.last())
        if (firstKeys.isEmpty() || lastKeys.isEmpty()) return emptyList()
        var maxFreq = 1
        if (dict != null) for (f in dict.freqs) if (f > maxFreq) maxFreq = f
        // The first and last few points count five times as much: where a glide starts and ends
        // says most about the word (tuned on simulated glides over the dictionary, ~93% top-1).
        val pointWeights = FloatArray(n) { if (it < 3 || it >= n - 3) 5f else 1f }
        val weightSum = pointWeights.sum()
        val wx = FloatArray(64)
        val wy = FloatArray(64)
        // Mean distance (in key widths) between the gesture and the key path spelling `seq`,
        // or -1 when the spelling cannot be the gesture.
        fun variantDistance(seq: String): Float {
            if (seq.length < 2 || seq.length > 40) return -1f
            if (seq[0] !in firstKeys || seq[seq.length - 1] !in lastKeys) return -1f
            var m = 0
            for (ch in seq) {
                val c = centers[ch] ?: return -1f
                if (m > 0 && wx[m - 1] == c[0] && wy[m - 1] == c[1]) continue
                wx[m] = c[0]
                wy[m] = c[1]
                m++
            }
            if (m < 2) return -1f
            val px = wx.copyOf(m)
            val py = wy.copyOf(m)
            val idealLen = pathLength(px, py)
            if (idealLen < gestureLen * 0.4f || idealLen > gestureLen * 2.5f) return -1f
            val ideal = resample(px, py, n)
            var sum = 0f
            for (k in 0 until n) {
                sum += pointWeights[k] * hypotf(ideal[2 * k] - gesture[2 * k], ideal[2 * k + 1] - gesture[2 * k + 1])
            }
            val distance = sum / weightSum / keyW
            return if (distance > 2.9f) -1f else distance
        }
        val scored = ArrayList<Pair<Float, String>>()
        if (dict != null) for (i in dict.words.indices) {
            val word = dict.words[i]
            var best = variantDistance(word)
            if (thai) {
                val reduced = word.filterNot { it in THAI_MARKS }
                if (reduced.length != word.length) {
                    val d = variantDistance(reduced)
                    if (d >= 0f && (best < 0f || d + 0.1f < best)) best = d + 0.1f
                }
            }
            if (best < 0f) continue
            val common = if (thai) {
                (Math.log(1.0 + dict.freqs[i]) / Math.log(1.0 + maxFreq)).toFloat()
            } else {
                dict.freqs[i] / maxFreq.toFloat()
            }
            scored.add((best - common * 0.8f) to word)
        }
        // Learned vocabulary joins the same contest; usage count breaks near-ties in its favour.
        for ((word, count) in userWords) {
            val best = variantDistance(word)
            if (best < 0f) continue
            val common = kotlin.math.min(1f, count * 0.3f)
            scored.add((best - common * 0.8f) to word)
        }
        scored.sortBy { it.first }
        return scored.take(3).map { it.second }
    }

    private fun commitGlide(words: List<String>, thai: Boolean) {
        val connection = currentInputConnection ?: return
        if (words.isEmpty()) return
        // A glide word is committed with the strip's capitalisation and spacing, so it can be
        // taken back by deleting what was just typed.
        fun styled(word: String) = when {
            shiftLocked -> word.uppercase()
            shifted -> word.replaceFirstChar { it.uppercase() }
            else -> word
        }
        if (thai) {
            // Thai has no spaces: the word goes in as is, and the runners-up replace it.
            val word = words[0]
            connection.commitText(word, 1)
            learnWord(word)
            glideCommitted = word
            glideAlternatives = words.map { Suggestion(it, word.length, "") }
            lastSpaceTap = 0L
            afterTextChanged()
            return
        }
        val shown = words.map { styled(it) }
        val previous = connection.getTextBeforeCursor(1, 0)?.toString()
        val lead = if (!previous.isNullOrEmpty() && (previous[0].isLetterOrDigit() || previous[0] in ".,!?;:")) " " else ""
        val committed = shown[0] + " "
        connection.commitText(lead + committed, 1)
        learnWord(shown[0])
        glideCommitted = committed
        glideAlternatives = shown.map { Suggestion(it, committed.length, " ") }
        lastSpaceTap = 0L
        afterTextChanged()
    }

    private fun applySuggestion(suggestion: Suggestion) {
        val connection = currentInputConnection ?: return
        if (glideCommitted != null) {
            preferences.edit().putInt(GLIDE_ALTERNATIVES, preferences.getInt(GLIDE_ALTERNATIVES, 0) + 1).apply()
        }
        connection.beginBatchEdit()
        connection.deleteSurroundingText(suggestion.replaceLen, 0)
        connection.commitText(suggestion.text + suggestion.append, 1)
        connection.endBatchEdit()
        // Picking a suggestion confirms the word, so it joins the personal dictionary too, and the
        // strip keeps offering it until the text changes.
        learnWord(suggestion.text)
        lastSpaceTap = 0L
        afterTextChanged()
    }

    // ---- Personal dictionary ---------------------------------------------------------------
    //
    // The typed word is the ground truth for what the user means, so words the user actually
    // commits get remembered and offered back. New words are only stored when they aren't already
    // in the shipped dictionary, keeping the personal list to genuinely new vocabulary.

    private fun loadUserWords() {
        userWords.clear()
        val raw = preferences.getString(USER_DICT, null) ?: return
        for (line in raw.lines()) {
            val trimmed = line.trim()
            if (trimmed.isEmpty()) continue
            val tab = trimmed.indexOf('\t')
            if (tab <= 0) continue
            val word = trimmed.substring(0, tab)
            val count = trimmed.substring(tab + 1).toIntOrNull() ?: 1
            if (word.isNotEmpty()) userWords[word] = count
        }
    }

    private fun saveUserWords() {
        val sb = StringBuilder()
        for ((word, count) in userWords) sb.append(word).append('\t').append(count).append('\n')
        preferences.edit().putString(USER_DICT, sb.toString()).apply()
    }

    private fun learnWord(word: String) {
        val trimmed = word.trim()
        if (trimmed.length < 2 || trimmed.length > 30) return
        val key = if (isThai(trimmed[0])) trimmed else trimmed.lowercase()
        val dict = if (isThai(trimmed[0])) dictTh else dictEn
        if (dict != null && dict.indexOf(key) >= 0) return
        userWords[key] = (userWords[key] ?: 0) + 1
        saveUserWords()
    }

    // Single-edit distance: substitution, insertion or deletion of one character.
    private fun oneEdit(a: String, b: String): Boolean {
        if (a == b) return false
        if (a.length == b.length) {
            var diff = 0
            for (k in a.indices) if (a[k] != b[k]) diff++
            return diff == 1
        }
        if (kotlin.math.abs(a.length - b.length) > 1) return false
        val long = if (a.length > b.length) a else b
        val short = if (a.length > b.length) b else a
        var i = 0
        var j = 0
        while (i < long.length && j < short.length) {
            if (long[i] == short[j]) { i++; j++ } else i++
            if (i - j > 1) return false
        }
        return true
    }

    // Learn whatever word the caret currently sits after: useful for Thai, whose words carry no
    // spaces to mark a boundary, and as a backstop for English words typed without a following
    // space. Best effort — safe to call when nothing usable is there.
    private fun learnTrailingWord() {
        val connection = currentInputConnection ?: return
        val before = connection.getTextBeforeCursor(24, 0)?.toString() ?: return
        if (before.isEmpty()) return
        if (isThai(before.last())) {
            var s = before.length
            while (s > 0 && isThai(before[s - 1])) s--
            val run = before.substring(s)
            if (run.length >= 2) learnWord(run)
        } else {
            var s = before.length
            while (s > 0 && (before[s - 1] in 'a'..'z' || before[s - 1] in 'A'..'Z' || before[s - 1] == '\'')) s--
            val word = before.substring(s)
            if (word.length >= 2) learnWord(word)
        }
    }

    private fun sendCtrl(keyCode: Int) {
        val connection = currentInputConnection ?: return
        val now = SystemClock.uptimeMillis()
        connection.sendKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_DOWN, keyCode, 0, KeyEvent.META_CTRL_ON))
        connection.sendKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_UP, keyCode, 0, KeyEvent.META_CTRL_ON))
        afterTextChanged()
    }

    // ---- Clipboard panel -----------------------------------------------------------------------

    private fun openClipboardPanel() {
        emojiPanelVisible = false
        clipSelection = null
        clipMenuId = null
        clipPanelVisible = true
        renderKeyboard()
    }

    private fun pasteClip(id: String) {
        val clip = clips.firstOrNull { it.id == id } ?: return
        val connection = currentInputConnection ?: return
        connection.commitText(clip.text, 1)
        clipMenuId = null
        clipSelection = null
        afterTextChanged()
    }

    // The paste tool and the panel both put the clip on the system clipboard, so a later long-press
    // on the text field pastes it the ordinary way.
    private fun putClipOnClipboard(text: String) {
        try {
            clipboardManager()?.setPrimaryClip(ClipData.newPlainText("DroidIME", text))
        } catch (_: Exception) {
            // Writing the clipboard is best effort; the panel still holds the clip.
        }
    }

    private fun deleteClip(id: String) {
        removeClip(id)
        if (clipMenuId == id) clipMenuId = null
        if (clipSelection == id) clipSelection = null
        renderKeyboard()
    }

    private fun togglePin(id: String) {
        toggleClipPin(id)
        clipMenuId = null
        renderKeyboard()
    }

    private fun showClipMenu(id: String) {
        clipMenuId = if (clipMenuId == id) null else id
        renderKeyboard()
    }

    // Edit mode is the "select many" state: tapping clips toggles them and the header button
    // deletes the selection, the way Gboard's clipboard edit mode does.
    private fun toggleClipSelection(id: String) {
        clipSelection = if (clipSelection == id) null else id
        renderKeyboard()
    }

    private fun toggleClipEdit() {
        clipEdit = !clipEdit
        clipSelection = null
        clipMenuId = null
        renderKeyboard()
    }

    private fun selectAllText() {
        currentInputConnection?.performContextMenuAction(android.R.id.selectAll)
    }

    // Long-pressing the cursor tool copies the selection, which is how a clip reaches the
    // clipboard panel without leaving the keyboard.
    private fun copySelectionTool() {
        val connection = currentInputConnection ?: return
        connection.performContextMenuAction(android.R.id.selectAll)
        val selected = connection.getSelectedText(0)
        if (selected.isNullOrEmpty()) return
        addClip(selected.toString(), pinned = false, system = false)
        putClipOnClipboard(selected.toString())
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
        clipPanelVisible = false; emojiPanelVisible = true; emojiCategory = EmojiCategory.FACES; renderKeyboard()
    }
    private fun closeEmojiPanel() { emojiPanelVisible = false; renderKeyboard() }
    private fun insertEmoji(emoji: String) {
        currentInputConnection?.commitText(emoji, 1)
        closeEmojiPanel()
        afterTextChanged()
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
        if (fieldKind == FieldKind.TEXT && value.isNotBlank() && value.length <= 4) {
            lastTypedToken = value
            lastTypedAt = SystemClock.elapsedRealtime()
        }
        afterTextChanged()
    }

    private fun touchHistory(): MutableList<JSONObject> {
        val raw = preferences.getString(TOUCH_HISTORY, "").orEmpty()
        return raw.lineSequence().mapNotNull {
            try { JSONObject(it) } catch (_: Exception) { null }
        }.toMutableList()
    }

    private fun saveTouchEvent(event: JSONObject) {
        if (fieldKind == FieldKind.PASSWORD || fieldKind == FieldKind.NUMBER ||
            fieldKind == FieldKind.PHONE || fieldKind == FieldKind.EMAIL || fieldKind == FieldKind.URL) return
        val events = touchHistory()
        events.add(event)
        while (events.size > 3000) events.removeAt(0)
        preferences.edit().putString(TOUCH_HISTORY, events.joinToString("\n") { it.toString() }).apply()
        lastTouchEventAt = SystemClock.elapsedRealtime()
    }

    private fun recordKeyTouch(x: Float, y: Float, viewWidth: Int, viewHeight: Int, cell: Cell?) {
        if (fieldKind != FieldKind.TEXT || viewWidth <= 0 || viewHeight <= 0) return
        val event = JSONObject()
            .put("type", "key_touch")
            .put("time", System.currentTimeMillis())
            .put("x", x / viewWidth)
            .put("y", y / viewHeight)
            .put("px", x.toDouble())
            .put("py", y.toDouble())
            .put("key", cell?.label ?: "")
            .put("key_x", cell?.rect?.centerX()?.div(viewWidth.toFloat()) ?: (x / viewWidth))
            .put("key_y", cell?.rect?.centerY()?.div(viewHeight.toFloat()) ?: (y / viewHeight))
            .put("corrected", false)
        saveTouchEvent(event)
    }

    private fun markLastTouchCorrected() {
        if (fieldKind != FieldKind.TEXT || SystemClock.elapsedRealtime() - lastTouchEventAt > 5000L) return
        val events = touchHistory()
        val glideIndex = events.indexOfLast { it.optString("type") == "glide_point" }
        val keyIndex = events.indexOfLast {
            it.optString("type") == "key_touch" && it.optString("key") !in setOf("space", "delete", "enter") &&
                !it.optBoolean("corrected")
        }
        if (glideIndex > keyIndex && glideIndex >= 0) {
            val gestureTime = events[glideIndex].optLong("time")
            events.filter { it.optString("type") == "glide_point" && it.optLong("time") == gestureTime }
                .forEach { it.put("rejected", true) }
        } else if (keyIndex >= 0) {
            events[keyIndex].put("corrected", true)
        }
        preferences.edit().putString(TOUCH_HISTORY, events.joinToString("\n") { it.toString() }).apply()
    }

    private fun recordGlidePath(points: List<Pair<Float, Float>>, viewWidth: Int, viewHeight: Int, rejected: Boolean) {
        if (fieldKind != FieldKind.TEXT || viewWidth <= 0 || viewHeight <= 0 || points.isEmpty()) return
        val now = System.currentTimeMillis()
        points.forEachIndexed { index, point ->
            saveTouchEvent(JSONObject()
                .put("type", "glide_point")
                .put("time", now)
                .put("index", index)
                .put("x", point.first / viewWidth)
                .put("y", point.second / viewHeight)
                .put("rejected", rejected))
        }
    }

    // Two quick spaces after a word become ". " (English only).
    private fun typeSpace() {
        val connection = currentInputConnection ?: return
        if (symbols) {
            // Like Gboard, a space typed on a symbol page drops back to the letters.
            symbols = false
            symbolPage = 0
            lastSpaceTap = 0L
            connection.commitText(" ", 1)
            shifted = computeAutoShift()
            renderKeyboard()
            return
        }
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
        // A word followed by a space is committed, so capture it for the personal dictionary.
        learnTrailingWord()
        typeText(" ")
    }

    private fun deleteOne(updateShift: Boolean = true) {
        val connection = currentInputConnection ?: return
        markLastTouchCorrected()
        // A word just produced by a glide (or picked from the strip) sits whole before the
        // cursor. Taking it back in one press matches Gboard, where delete removes the word the
        // suggestion strip just offered rather than its last character. This applies to the first
        // press and to the repeat that follows it, so holding delete does not nibble the word.
        val tail = glideCommitted
        if (tail != null && connection.getTextBeforeCursor(tail.length, 0)?.toString() == tail) {
            connection.deleteSurroundingText(tail.length, 0)
            glideCommitted = null
            glideAlternatives = emptyList()
            if (updateShift) afterTextChanged()
            return
        }
        glideCommitted = null
        glideAlternatives = emptyList()
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
        val clipId: String? = null,
        val rect: RectF = RectF(),
        // Gap-free hit area: tiles the whole keyboard so no tap lands on dead space.
        val touchRect: RectF = RectF()
    ) {
        fun withAction(action: () -> Unit): Cell = Cell(
            label, style, hint, action, longAction, icon, emojiFace, isDelete, glyph, pill, clipId
        )

        fun withLongAction(action: () -> Unit): Cell = Cell(
            label, style, hint, this.action, action, icon, emojiFace, isDelete, glyph, pill, clipId
        )

        val contentDescription: String = describe(label, style)

        companion object {
            fun describe(label: String, style: KeyStyle): String = when {
                style == KeyStyle.SPACE -> "Space"
                style == KeyStyle.HEADER || style == KeyStyle.HEADER_CLOSE -> label
                style == KeyStyle.EMOJI -> "Emoji $label"
                style == KeyStyle.EMOJI_CATEGORY -> "Emoji category ${EmojiCategory.values().firstOrNull { it.icon == label }?.title ?: label}"
                style == KeyStyle.CLIP -> label.ifEmpty { "Empty clipboard slot" }
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
        private var spaceDragMoved = false
        private var glideStartCell: Cell? = null
        private var glideActive = false
        private var glideLength = 0f
        private val glideX = ArrayList<Float>()
        private val glideY = ArrayList<Float>()
        private val trailPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
            color = Color.argb(200, 138, 180, 248)
        }
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
            if (glideActive && glideX.size > 1) {
                val trail = Path()
                trail.moveTo(glideX[0], glideY[0])
                for (i in 1 until glideX.size) trail.lineTo(glideX[i], glideY[i])
                trailPaint.strokeWidth = dpf(5f)
                canvas.drawPath(trail, trailPaint)
            }
        }

        // Each row is a list of (weight, cell) slots; a null cell is an indent spacer.
        private fun buildRows(): List<List<Pair<Float, Cell?>>> {
            if (emojiPanelVisible) return buildEmojiRows()
            if (clipOpen()) return buildClipRows()
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

        // Top strip, as in Gboard: the left (grid) and right (cursor) buttons are always there at
        // fixed positions; between them sit three suggestion slots while there are suggestions
        // (blank slots fill up to three) and the tool icons otherwise.
        private fun stripCells(): List<Cell> {
            fun tool(icon: ToolIcon, name: String): Cell =
                Cell(name, KeyStyle.TOOLBAR, null, icon = icon, action = {})
            val middle: List<Cell> = if (suggestions.isEmpty()) {
                listOf(
                    tool(ToolIcon.UNDO, "Undo").withAction { sendCtrl(KeyEvent.KEYCODE_Z) },
                    tool(ToolIcon.TRANSLATE, "Switch language").withAction { switchLanguage() },
                    tool(ToolIcon.CLIPBOARD, "Clipboard").withAction { openClipboardPanel() },
                    tool(ToolIcon.SETTINGS, "Keyboard settings").withAction { openSettings() }
                )
            } else {
                List(3) { i ->
                    val s = suggestions.getOrNull(i)
                    if (s != null) {
                        Cell(s.text, KeyStyle.SUGGESTION, null, action = { applySuggestion(s) })
                    } else {
                        Cell("", KeyStyle.SUGGESTION, null, action = {})
                    }
                }
            }
            // The cursor tool selects all on a tap and copies the selection on a long-press, which
            // is how a clip reaches the clipboard panel from the keyboard alone.
            val cursor = tool(ToolIcon.SELECT, "Select all")
                .withAction { selectAllText() }
                .withLongAction { copySelectionTool() }
            val grid = tool(ToolIcon.GRID, "Choose keyboard")
                .withAction { showKeyboardPicker() }
            return listOf(grid) + middle + cursor
        }

        // The clipboard panel: a header (title, edit, close), then one row per clip. Pinned clips
        // come first with a pin marker, like Gboard's two sections. Rows are full-width and their
        // height is the panel height divided among them, so the panel always fills the keyboard.
        private fun buildClipRows(): List<List<Pair<Float, Cell?>>> {
            val rows = mutableListOf<List<Pair<Float, Cell?>>>()
            val panel = clipsForPanel()
            val pinned = panel.filter { clip -> clip.pinned }
            val recent = panel.filter { clip -> !clip.pinned }
            rows.add(listOf(3f to headerCell(), 1f to closeClipboardCell()))
            if (panel.isEmpty()) {
                rows.add(listOf(1f to emptyCell()))
                return rows
            }
            if (pinned.isNotEmpty()) {
                rows.add(listOf(1f to sectionCell("Pinned")))
                pinned.forEach { rows.add(listOf(1f to clipCell(it.id))) }
            }
            if (recent.isNotEmpty()) {
                rows.add(listOf(1f to sectionCell("Recent")))
                recent.forEach { rows.add(listOf(1f to clipCell(it.id))) }
            }
            return rows
        }

        private fun headerCell(): Cell {
            val label = when { clipEdit && clipSelection != null -> "Delete"; clipEdit -> "Done"; else -> "Edit" }
            return Cell(label, KeyStyle.HEADER, null, action = {
                val selected = clipSelection
                if (clipEdit && selected != null) deleteClip(selected) else toggleClipEdit()
            })
        }
        private fun closeClipboardCell(): Cell = Cell("Close", KeyStyle.HEADER_CLOSE, null, action = {
            clipPanelVisible = false; clipEdit = false; clipSelection = null; clipMenuId = null; renderKeyboard()
        })

        private fun sectionCell(name: String): Cell =
            Cell(name, KeyStyle.SECTION, null, action = {}, longAction = {})

        private fun emptyCell(): Cell =
            Cell("No copies yet", KeyStyle.CLIP, null, action = {})

        // A clip row. The long-press menu (the way Gboard opens it) is drawn inside the row, and
        // the pin state is shown with a marker at the left edge.
        private fun clipCell(id: String): Cell {
            val clip = clips.firstOrNull { found -> found.id == id } ?: return emptyCell()
            val menu = clipMenuId == id
            val selected = clipSelection == id
            // Only the actions that apply to this clip are offered: an unpinned clip can be
            // pinned, a pinned one unpinned, and either can be deleted.
            val menuLabel = if (clip.pinned) "Unpin\nDelete" else "Pin\nDelete"
            val label = when {
                menu -> menuLabel
                selected -> "✓ ${clipPreview(clip.text)}"
                else -> clipPreview(clip.text)
            }
            return Cell(
                label, KeyStyle.CLIP, null,
                clipId = id,
                action = {
                    when {
                        menu -> clipMenuId = null
                        clipEdit -> toggleClipSelection(id)
                        selected -> toggleClipSelection(id)
                        else -> pasteClip(id)
                    }
                },
                longAction = {
                    if (menu) clipMenuId = null else showClipMenu(id)
                }
            )
        }

        // Positions measured from Gboard (1080px wide): button centres at 6.6% and 93.2%, the three
        // slots span 8.6%..90.8% (dividers at 36.0% and 63.4%); tool icons keep 17.3% spacing.
        private fun placeStrip(strip: List<Cell>, out: MutableList<Cell>) {
            if (strip.size < 3) return
            val bandH = dpf(STRIP_BAND_DP)
            val centerY = bandH / 2f
            val w = width.toFloat()
            val half = dpf(12f)
            val first = strip.first()
            val last = strip.last()
            val middle = strip.subList(1, strip.size - 1)
            val toolMode = middle[0].style == KeyStyle.TOOLBAR
            val pitch = w * 0.1731f
            val innerLeft = if (toolMode) w * 0.0667f + pitch * 0.5f else w * 0.0861f
            val innerRight = if (toolMode) w * 0.0667f + pitch * (middle.size + 0.5f) else w * 0.9083f
            first.rect.set(w * 0.0667f - half, centerY - half, w * 0.0667f + half, centerY + half)
            first.touchRect.set(0f, 0f, innerLeft, bandH)
            last.rect.set(w * 0.9317f - half, centerY - half, w * 0.9317f + half, centerY + half)
            last.touchRect.set(innerRight, 0f, w, bandH)
            out.add(first)
            if (toolMode) {
                middle.forEachIndexed { i, cell ->
                    val cx = w * 0.0667f + pitch * (i + 1)
                    cell.rect.set(cx - half, centerY - half, cx + half, centerY + half)
                    cell.touchRect.set(cx - pitch / 2f, 0f, cx + pitch / 2f, bandH)
                    out.add(cell)
                }
            } else {
                val slot = (innerRight - innerLeft) / middle.size
                middle.forEachIndexed { i, cell ->
                    cell.rect.set(innerLeft + slot * i, 0f, innerLeft + slot * (i + 1), bandH)
                    cell.touchRect.set(cell.rect)
                    out.add(cell)
                }
            }
            out.add(last)
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
                    action = { if (emojiKey) showEmoji() else typeText(leftSide) },
                    longAction = if (emojiKey) ({ typeText(",") }) else null
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
            if (emojiPanelVisible) { layoutEmojiRows(rows, flat); return }
            if (panelOpen()) { layoutClipRows(rows, flat); return }
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

        // The panel fills the whole keyboard: a header row at the top and the clip rows sharing
        // what is left, so a long list still fits and every row stays tappable.
        private fun layoutClipRows(rows: List<List<Pair<Float, Cell?>>>, flat: MutableList<Cell>) {
            val padBottom = dpf(8f) + navigationInset
            val availH = (height - dpf(CLIP_PAD_TOP_DP) - padBottom).coerceAtLeast(1f)
            val headerH = dpf(CLIP_HEADER_DP); val gap = dpf(4f)
            val bodyRows = (rows.size - 1).coerceAtLeast(1)
            val rowH = ((availH - headerH - gap * (rows.size - 1)) / bodyRows).coerceAtLeast(dpf(20f))
            var top = dpf(CLIP_PAD_TOP_DP)
            rows.forEachIndexed { index, row ->
                val h = if (index == 0) headerH else rowH
                val bottom = (top + h).coerceAtMost((height - padBottom).coerceAtLeast(top + 1f))
                val totalWeight = row.sumOf { it.first.toDouble() }.toFloat().coerceAtLeast(1f)
                val leftEdge = dpf(8f); val usableWidth = (width - dpf(16f)).coerceAtLeast(1f)
                var left = leftEdge
                row.forEach { (weight, cell) ->
                    val right = left + usableWidth * weight / totalWeight
                    if (cell != null) {
                        cell.rect.set(left, top, right, bottom)
                        val bandTop = if (index == 0) 0f else top - gap / 2f
                        val bandBottom = if (index == rows.lastIndex) height.toFloat() else bottom + gap / 2f
                        cell.touchRect.set(left, bandTop, right, bandBottom); flat.add(cell)
                    }
                    left = right
                }
                top = bottom + gap
            }
            cells = flat
        }

        private fun buildEmojiRows(): List<List<Pair<Float, Cell?>>> {
            val rows = mutableListOf<List<Pair<Float, Cell?>>>()
            rows.add(listOf(3f to Cell("Emoji", KeyStyle.HEADER, null, action = {}),
                1f to Cell("Close", KeyStyle.HEADER_CLOSE, null, action = { closeEmojiPanel() })))
            rows.add(EmojiCategory.values().map { category ->
                1f to Cell(category.icon, KeyStyle.EMOJI_CATEGORY, null, action = { emojiCategory = category; renderKeyboard() })
            })
            emojiCategory.items.chunked(8).forEach { group ->
                rows.add(group.map { emoji -> 1f to Cell(emoji, KeyStyle.EMOJI, null, action = { insertEmoji(emoji) }) })
            }
            return rows
        }

        private fun layoutEmojiRows(rows: List<List<Pair<Float, Cell?>>>, flat: MutableList<Cell>) {
            if (rows.size < 3) { cells = flat; return }
            val bottom = (height - dpf(8f) - navigationInset).coerceAtLeast(1f)
            val top = dpf(6f); val headerH = dpf(42f); val categoryH = dpf(42f); val gap = dpf(3f)
            val gridTop = top + headerH + gap + categoryH + gap
            val gridRows = (rows.size - 2).coerceAtLeast(1)
            val rowH = ((bottom - gridTop) / gridRows).coerceAtLeast(dpf(26f))
            rows.forEachIndexed { index, row ->
                val y = when (index) { 0 -> top; 1 -> top + headerH + gap; else -> gridTop + (index - 2) * rowH }
                val h = when (index) { 0 -> headerH; 1 -> categoryH; else -> rowH }
                val y2 = (y + h).coerceAtMost(bottom)
                val totalWeight = row.sumOf { it.first.toDouble() }.toFloat().coerceAtLeast(1f)
                val usable = (width - dpf(8f)).coerceAtLeast(1f); var x = dpf(4f)
                row.forEach { (weight, cell) ->
                    val x2 = x + usable * weight / totalWeight
                    if (cell != null) { cell.rect.set(x, y, x2, y2); cell.touchRect.set(x, if (index == 0) 0f else y, x2, y2); flat.add(cell) }
                    x = x2
                }
            }
            cells = flat
        }

        private fun drawEmojiPanelCell(canvas: Canvas, cell: Cell, colors: Palette) {
            val selected = cell.style == KeyStyle.EMOJI_CATEGORY && cell.label == emojiCategory.icon
            shape.setColor(if (cell === pressed || selected) colors.active else colors.key)
            shape.setBounds((cell.rect.left + dpf(2f)).toInt(), (cell.rect.top + dpf(2f)).toInt(),
                (cell.rect.right - dpf(2f)).toInt(), (cell.rect.bottom - dpf(2f)).toInt())
            shape.cornerRadius = if (cell.style == KeyStyle.EMOJI) dpf(8f) else dpf(12f); shape.draw(canvas)
            textPaint.color = if (selected) colors.enterText else colors.text
            textPaint.typeface = if (cell.style == KeyStyle.EMOJI_CATEGORY) mediumFace else Typeface.DEFAULT
            textPaint.textSize = dpf(if (cell.style == KeyStyle.EMOJI) 25f else 20f)
            textPaint.textAlign = Paint.Align.CENTER
            val m = textPaint.fontMetrics
            canvas.drawText(cell.label, cell.rect.centerX(), cell.rect.centerY() - (m.ascent + m.descent) / 2f, textPaint)
            textPaint.typeface = Typeface.DEFAULT
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
            if (cell.style == KeyStyle.EMOJI || cell.style == KeyStyle.EMOJI_CATEGORY) { drawEmojiPanelCell(canvas, cell, colors); return }
            if (cell.style == KeyStyle.CLIP || cell.style == KeyStyle.HEADER ||
                cell.style == KeyStyle.HEADER_CLOSE || cell.style == KeyStyle.SECTION
            ) {
                drawClipCell(canvas, cell, colors)
                return
            }
            shape.setColor(
                if (cell === pressed) colors.active else when (cell.style) {
                    KeyStyle.NORMAL -> colors.key
                    KeyStyle.SPECIAL -> colors.special
                    KeyStyle.ACTIVE -> colors.active
                    KeyStyle.SPACE -> colors.key
                    KeyStyle.ENTER -> colors.enter
                    KeyStyle.TOOLBAR, KeyStyle.SUGGESTION, KeyStyle.CLIP, KeyStyle.HEADER,
                    KeyStyle.HEADER_CLOSE, KeyStyle.SECTION, KeyStyle.EMOJI, KeyStyle.EMOJI_CATEGORY -> colors.background
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
            // Dividers sit between slots only (Gboard has none at the strip's left edge).
            if (cell.rect.left > width * 0.1f) {
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

        // Clipboard panel rows. A clip row is a rounded card with the text left-aligned and, when
        // pinned, a pin marker; the long-press menu replaces the row's text with its actions.
        private fun drawClipCell(canvas: Canvas, cell: Cell, colors: Palette) {
            val ink = colors.text
            if (cell.style == KeyStyle.SECTION) {
                // A small muted label above a group of clips; it is not itself tappable-looking.
                textPaint.color = colors.hint
                textPaint.typeface = Typeface.DEFAULT
                textPaint.textSize = dpf(12f)
                textPaint.textAlign = Paint.Align.LEFT
                val m = textPaint.fontMetrics
                canvas.drawText(
                    cell.label, cell.rect.left + dpf(14f),
                    cell.rect.centerY() - (m.ascent + m.descent) / 2f, textPaint
                )
                textPaint.textAlign = Paint.Align.CENTER
                return
            }
            if (cell.style == KeyStyle.HEADER_CLOSE) {
                textPaint.color = ink; textPaint.textSize = dpf(24f); textPaint.textAlign = Paint.Align.CENTER
                val m = textPaint.fontMetrics
                canvas.drawText("×", cell.rect.centerX(), cell.rect.centerY() - (m.ascent + m.descent) / 2f, textPaint)
                return
            }
            if (cell.style == KeyStyle.HEADER) {
                // Header: title on the left, the action (edit/clear) on the right.
                textPaint.color = ink
                textPaint.typeface = mediumFace
                textPaint.textSize = dpf(16f)
                val m = textPaint.fontMetrics
                val cy = cell.rect.centerY() - (m.ascent + m.descent) / 2f
                textPaint.textAlign = Paint.Align.LEFT
                canvas.drawText(if (emojiPanelVisible) "Emoji" else "Clipboard", cell.rect.left + dpf(14f), cy, textPaint)
                textPaint.textAlign = Paint.Align.RIGHT
                canvas.drawText(cell.label, cell.rect.right - dpf(14f), cy, textPaint)
                textPaint.textAlign = Paint.Align.CENTER
                textPaint.typeface = Typeface.DEFAULT
                return
            }
            val selected = clipSelection == cell.clipId
            val menu = clipMenuId == cell.clipId
            shape.setColor(if (selected) colors.active else colors.key)
            shape.setBounds(
                (cell.rect.left + dpf(6f)).toInt(), (cell.rect.top + dpf(2f)).toInt(),
                (cell.rect.right - dpf(6f)).toInt(), (cell.rect.bottom - dpf(2f)).toInt()
            )
            shape.cornerRadius = cornerRadius
            shape.draw(canvas)
            val clip = clips.firstOrNull { it.id == cell.clipId }
            if (clip != null && clip.pinned) {
                iconFill.color = colors.hint
                val px = cell.rect.left + dpf(16f)
                val py = cell.rect.centerY()
                canvas.drawCircle(px, py - dpf(2f), dpf(2.2f), iconFill)
                canvas.drawLine(px, py, px, py + dpf(4f), iconFill)
            }
            textPaint.color = if (selected) colors.enterText else ink
            textPaint.typeface = Typeface.DEFAULT
            if (menu) {
                val lines = cell.label.split('\n')
                textPaint.textSize = dpf(15f)
                val m = textPaint.fontMetrics
                val lineH = m.descent - m.ascent
                val top = cell.rect.centerY() - lineH * (lines.size - 1) / 2f - m.ascent
                textPaint.textAlign = Paint.Align.CENTER
                lines.forEachIndexed { i, line ->
                    canvas.drawText(line, cell.rect.centerX(), top + lineH * i, textPaint)
                }
                return
            }
            val text = cell.label
            textPaint.textSize = dpf(15f)
            val maxW = cell.rect.width() - dpf(40f)
            var shown = text
            while (shown.length > 1 && textPaint.measureText(shown) > maxW) shown = shown.dropLast(1)
            if (shown.length < text.length) shown = shown.dropLast(1) + "…"
            val m = textPaint.fontMetrics
            textPaint.textAlign = Paint.Align.LEFT
            canvas.drawText(
                shown, cell.rect.left + dpf(28f),
                cell.rect.centerY() - (m.ascent + m.descent) / 2f, textPaint
            )
            textPaint.textAlign = Paint.Align.CENTER
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

        // A clip row's long-press menu is drawn inside the row, so the row is split into the menu
        // entries top to bottom; the tap is routed to the entry it lands on.
        private fun clipMenuAction(cell: Cell, y: Float): (() -> Unit)? {
            if (cell.clipId == null || clipMenuId != cell.clipId) return null
            val id = cell.clipId
            val lines = cell.label.split('\n')
            if (lines.size < 2) return null
            val step = cell.rect.height() / lines.size
            val index = ((y - cell.rect.top) / step).toInt().coerceIn(0, lines.size - 1)
            return when (lines[index]) {
                "Pin", "Unpin" -> ({ togglePin(id) })
                "Delete" -> ({ deleteClip(id) })
                "Paste" -> ({ pasteClip(id) })
                else -> null
            }
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    val cell = cellAt(event.x, event.y)
                    if (!panelOpen()) recordKeyTouch(event.x, event.y, width, height, cell)
                    pressed = cell
                    longPressFired = false
                    spaceDragMoved = false
                    if (cell?.style == KeyStyle.SPACE && !panelOpen()) {
                        val extracted = currentInputConnection?.getExtractedText(ExtractedTextRequest(), 0)
                        if (extracted != null && extracted.selectionStart >= 0) {
                            spaceDragStartX = event.x
                            spaceDragStartCursor = extracted.startOffset + extracted.selectionStart
                            spaceDragLastCursor = spaceDragStartCursor
                        } else spaceDragStartCursor = -1
                    } else spaceDragStartCursor = -1
                    invalidate()
                    glideActive = false
                    glideStartCell = null
                    glideLength = 0f
                    glideX.clear()
                    glideY.clear()
                    if (cell != null && panelOpen()) {
                        keyFeedback(this)
                        if (cell.style == KeyStyle.SECTION) return true
                        // The panel has no glide and no delete repeat; every row is a plain tap.
                        val menuAction = if (clipOpen()) clipMenuAction(cell, event.y) else null
                        val longAction = menuAction ?: cell.longAction
                        if (longAction != null) {
                            val runnable = Runnable {
                                longPressFired = true
                                longAction.invoke()
                            }
                            pendingLongPress = runnable
                            pressHandler.postDelayed(runnable, 400L)
                        }
                        return true
                    }
                    if (cell != null && glideAllowed() && cell.style == KeyStyle.NORMAL &&
                        cell.label.length == 1 && isGlideLetter(cell.label[0])) {
                        glideStartCell = cell
                        glideX.add(event.x)
                        glideY.add(event.y)
                    }
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
                    if (spaceDragStartCursor >= 0 && pressed?.style == KeyStyle.SPACE) {
                        val extracted = currentInputConnection?.getExtractedText(ExtractedTextRequest(), 0)
                        if (extracted != null && extracted.selectionStart >= 0) {
                            val steps = kotlin.math.round((event.x - spaceDragStartX) / dpf(10f)).toInt()
                            val target = (spaceDragStartCursor + steps).coerceIn(extracted.startOffset, extracted.startOffset + (extracted.text?.length ?: 0))
                            if (target != spaceDragLastCursor && currentInputConnection?.setSelection(target, target) == true) {
                                spaceDragLastCursor = target
                                spaceDragMoved = true
                                saveTouchEvent(JSONObject().put("type", "cursor_drag").put("time", System.currentTimeMillis())
                                    .put("x", event.x / width.coerceAtLeast(1)).put("y", event.y / height.coerceAtLeast(1))
                                    .put("cursor", target))
                            }
                        }
                        return true
                    }
                    if (panelOpen()) {
                        val cell = cellAt(event.x, event.y)
                        if (cell !== pressed) {
                            pressed = cell
                            cancelPendingLongPress()
                            invalidate()
                        }
                        return true
                    }
                    if (glideStartCell != null) {
                        for (h in 0 until event.historySize) addGlidePoint(event.getHistoricalX(h), event.getHistoricalY(h))
                        addGlidePoint(event.x, event.y)
                        if (!glideActive && glideLength >= dpf(28f) && cellAt(event.x, event.y) !== glideStartCell) {
                            glideActive = true
                            cancelPendingLongPress()
                            pressed = null
                        }
                        if (glideActive) {
                            invalidate()
                            return true
                        }
                    }
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
                    if (panelOpen()) {
                        val cell = pressed
                        pressed = null
                        invalidate()
                        if (cell != null && cell.style != KeyStyle.SECTION && !longPressFired &&
                            cellAt(event.x, event.y) === cell
                        ) {
                            val menuAction = if (clipOpen()) clipMenuAction(cell, event.y) else null
                            if (menuAction != null) menuAction() else cell.action()
                        }
                        longPressFired = false
                        return true
                    }
                    if (glideActive) {
                        addGlidePoint(event.x, event.y)
                        val xs = glideX.toFloatArray()
                        val ys = glideY.toFloatArray()
                        recordGlidePath(glideX.indices.map { glideX[it] to glideY[it] }, width, height, false)
                        glideActive = false
                        glideStartCell = null
                        glideX.clear()
                        glideY.clear()
                        pressed = null
                        invalidate()
                        commitGlide(
                            glideCandidates(xs, ys, letterCenters(), letterKeyWidth(), language == Language.THAI),
                            language == Language.THAI
                        )
                        return true
                    }
                    glideStartCell = null
                    cancelPendingLongPress()
                    val wasRepeating = deleteRepeat != null
                    stopDeleteRepeat()
                    val cell = pressed
                    pressed = null
                    invalidate()
                    if (cell != null && !longPressFired && !spaceDragMoved && !cell.isDelete &&
                        cellAt(event.x, event.y) === cell) {
                        cell.action()
                    }
                    spaceDragStartCursor = -1
                    spaceDragMoved = false
                    if (wasRepeating) post { afterTextChanged() }
                    longPressFired = false
                    return true
                }
                MotionEvent.ACTION_CANCEL -> {
                    glideActive = false
                    glideStartCell = null
                    spaceDragStartCursor = -1
                    spaceDragMoved = false
                    glideX.clear()
                    glideY.clear()
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

        private fun addGlidePoint(x: Float, y: Float) {
            if (glideX.isNotEmpty()) {
                glideLength += hypotf(x - glideX.last(), y - glideY.last())
            }
            glideX.add(x)
            glideY.add(y)
            // Exact normalized pointer coordinates are saved when the glide ends.
        }

        private fun isGlideLetter(ch: Char): Boolean =
            if (language == Language.THAI) isThai(ch) else ch.lowercaseChar() in 'a'..'z'

        // Key centres by character. On the Thai keyboard a key also stands for its shift-layer
        // character, so words spelled with either layer can be matched whichever one is showing.
        private fun letterCenters(): Map<Char, FloatArray> {
            val map = HashMap<Char, FloatArray>()
            val baseOf = thaiShift.entries.associate { it.value to it.key }
            for (cell in cells) {
                if (cell.style != KeyStyle.NORMAL || cell.label.length != 1) continue
                val center = floatArrayOf(cell.rect.centerX(), cell.rect.centerY())
                if (language == Language.THAI) {
                    val base = baseOf[cell.label] ?: cell.label
                    if (!isThai(base[0])) continue
                    map[base[0]] = center
                    thaiShift[base]?.let { map[it[0]] = center }
                } else {
                    val letter = cell.label[0].lowercaseChar()
                    if (letter in 'a'..'z') map[letter] = center
                }
            }
            return map
        }

        private fun letterKeyWidth(): Float {
            for (cell in cells) {
                if (cell.style == KeyStyle.NORMAL && cell.label.length == 1 && isGlideLetter(cell.label[0])) {
                    return cell.rect.width()
                }
            }
            return dpf(36f)
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
        // Clipboard rows announce their pin state, since the marker alone is easy to miss.
        private fun clipDescription(cell: Cell): String {
            if (cell.style == KeyStyle.SECTION) return cell.label
            if (cell.style != KeyStyle.CLIP || cell.clipId == null) return cell.contentDescription
            val clip = clips.firstOrNull { found -> found.id == cell.clipId }
            val state = when {
                clipMenuId == cell.clipId -> "options"
                clip?.pinned == true -> "pinned"
                else -> "recent"
            }
            return "${cell.label}, $state"
        }

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
            info.contentDescription = clipDescription(cell)
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
        private const val EDIT_PENALTY = 5
        private const val THAI_MARKS = "ัิีึืุู็่้๊๋์ํ"
        private const val THAI_NON_LEADING ="ะาำัิีึืุู็่้๊๋์ํๅๆฯฺ"

        private val SETTINGS_KEYS = setOf(
            "keyboard_height_dp", "theme", "number_row_en", "number_row_th", "key_hints", "suggestions",
            "clipboard"
        )

        private const val USER_DICT = "user_dict"
        private const val TOUCH_HISTORY = "touch_history"
        private const val GLIDE_ALTERNATIVES = "glide_alternatives"
        private const val CLIP_DICT = "clipboard_items"
        private const val CLIP_KEEP = 12
        private const val CLIP_STORE_LIMIT = 20_000
        private const val CLIP_HEADER_DP = 44f
        private const val CLIP_PAD_TOP_DP = 10f

        private const val DELETE_INITIAL_DELAY_MS = 400L
        private const val DELETE_START_MS = 60
        private const val DELETE_ACCEL_STEP = 3
        private const val DELETE_MIN_MS = 15
    }
}
