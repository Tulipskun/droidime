package com.tulipskun.droidime

import android.view.inputmethod.BaseInputConnection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LogicTest {
    private fun setField(target: Any, name: String, value: Any?) {
        var c: Class<*>? = target.javaClass
        while (c != null) {
            val f = c.declaredFields.firstOrNull { it.name == name }
            if (f != null) { f.isAccessible = true; f.set(target, value); return }
            c = c.superclass
        }
        throw NoSuchFieldException(name)
    }

    private fun getField(target: Any, name: String): Any? {
        var c: Class<*>? = target.javaClass
        while (c != null) {
            val f = c.declaredFields.firstOrNull { it.name == name }
            if (f != null) { f.isAccessible = true; return f.get(target) }
            c = c.superclass
        }
        throw NoSuchFieldException(name)
    }

    private fun call(target: Any, name: String, vararg args: Any?): Any? {
        val m = target.javaClass.declaredMethods.firstOrNull { it.name == name && it.parameterCount == args.size }
            ?: throw NoSuchMethodException(name)
        m.isAccessible = true
        return m.invoke(target, *args)
    }

    private fun clip(service: DroidImeService, id: String, text: String, pinned: Boolean): Any {
        val cls = Class.forName("com.tulipskun.droidime.DroidImeService\$Clip")
        val ctor = cls.declaredConstructors.first(); ctor.isAccessible = true
        return ctor.newInstance(id, text, pinned, 0L)
    }

    @Test fun thaiCornerHintsMatchGboardLongPressSymbols() {
        val service = Robolectric.buildService(DroidImeService::class.java).create().get()
        val hints = getField(service, "thaiHints") as Map<*, *>
        assertEquals("1", hints["ๅ"])
        assertEquals("0", hints["ช"])
        assertEquals("=", hints["ๆ"])
        assertEquals("&", hints["ร"])
        assertEquals("!", hints["ว"])
        assertEquals("<", hints["ใ"])
        assertFalse("vowel-only keys have no long-press hint", hints.containsKey("ุ"))
    }

    @Test fun clipPreviewCollapsesWhitespace() {
        val service = Robolectric.buildService(DroidImeService::class.java).create().get()
        assertEquals("a b c", call(service, "clipPreview", "a\n\nb  c") as String)
    }

    @Test fun clipsForPanelPutsPinnedFirst() {
        val service = Robolectric.buildService(DroidImeService::class.java).create().get()
        val list = ArrayList<Any>()
        list.add(clip(service, "1", "recent one", false))
        list.add(clip(service, "2", "pinned one", true))
        list.add(clip(service, "3", "recent two", false))
        setField(service, "clips", list)
        val panel = call(service, "clipsForPanel") as List<*>
        assertEquals("pinned one", getField(panel[0]!!, "text"))
        assertEquals("recent one", getField(panel[1]!!, "text"))
        assertEquals("recent two", getField(panel[2]!!, "text"))
    }

    @Test fun clipPreviewTruncatesLongText() {
        val service = Robolectric.buildService(DroidImeService::class.java).create().get()
        val long = "x".repeat(200)
        val out = call(service, "clipPreview", long) as String
        assertTrue("expected truncation, got length ${out.length}", out.length <= 81)
        assertTrue(out.endsWith("…"))
    }

    @Test fun panelVisibilityNeedsTheSetting() {
        val service = Robolectric.buildService(DroidImeService::class.java).create().get()
        setField(service, "clipPanelVisible", true)
        assertTrue(call(service, "clipOpen") as Boolean)
        service.getSharedPreferences("keyboard_settings", android.content.Context.MODE_PRIVATE)
            .edit().putBoolean("clipboard", false).apply()
        assertFalse(call(service, "clipOpen") as Boolean)
    }

    @Test fun thaiComposingInputAcceptsThaiMarksButNotBoundaries() {
        val service = Robolectric.buildService(DroidImeService::class.java).create().get()
        assertTrue(call(service, "isThaiText", "ภาษาไทย") as Boolean)
        assertTrue(call(service, "isThaiText", "กำลัง") as Boolean)
        assertFalse(call(service, "isThaiText", "ไทย ") as Boolean)
        assertTrue(call(service, "isThaiText", "ไทยๆ") as Boolean)
    }

    @Test fun thaiSuggestionsUseLearnedNextWordAfterSpace() {
        val service = Robolectric.buildService(DroidImeService::class.java).create().get()
        val cls = Class.forName("com.tulipskun.droidime.DroidImeService\$Dictionary")
        val ctor = cls.declaredConstructors.first(); ctor.isAccessible = true
        val dict = ctor.newInstance(arrayOf("สวัสดี", "ขอบคุณ", "ภาษา"), intArrayOf(100, 90, 80))
        setField(service, "dictTh", dict)
        call(service, "learnWordTransition", "สวัสดี", "ขอบคุณ")
        val out = call(service, "thaiSuggestions", "สวัสดี ") as List<*>
        assertEquals("ขอบคุณ", getField(out[0]!!, "text"))
    }

    @Test fun thaiComposingUnderlineMovesToTheNextWord() {
        val service = Robolectric.buildService(DroidImeService::class.java).create().get()
        val editor = android.widget.EditText(Robolectric.buildActivity(android.app.Activity::class.java).get())
        val connection = BaseInputConnection(editor, true)
        setField(service, "mInputConnection", connection)
        val cls = Class.forName("com.tulipskun.droidime.DroidImeService\$Dictionary")
        val ctor = cls.declaredConstructors.first(); ctor.isAccessible = true
        setField(service, "dictTh", ctor.newInstance(arrayOf("สวัสดี"), intArrayOf(100)))
        val language = Class.forName("com.tulipskun.droidime.DroidImeService\$Language")
        setField(service, "language", language.enumConstants!!.first { (it as Enum<*>).name == "THAI" })

        for (ch in "สวัสดีครับ") call(service, "typeText", ch.toString())

        assertEquals("สวัสดีครับ", connection.getTextBeforeCursor(20, 0).toString())
        assertEquals("ครับ", getField(service, "thaiComposingText"))
    }

    @Test fun thaiNextWordIsLearnedFromTypingWithSpace() {
        val service = Robolectric.buildService(DroidImeService::class.java).create().get()
        val editor = android.widget.EditText(Robolectric.buildActivity(android.app.Activity::class.java).get())
        val connection = BaseInputConnection(editor, true)
        setField(service, "mInputConnection", connection)
        val cls = Class.forName("com.tulipskun.droidime.DroidImeService\$Dictionary")
        val ctor = cls.declaredConstructors.first(); ctor.isAccessible = true
        setField(
            service, "dictTh",
            ctor.newInstance(arrayOf("สวัสดี", "ขอบคุณ", "ภาษา"), intArrayOf(100, 90, 80))
        )

        call(service, "typeText", "สวัสดี")
        call(service, "typeSpace")
        call(service, "typeText", "ขอบคุณ")
        call(service, "typeSpace")

        val out = call(service, "thaiSuggestions", "สวัสดี ") as List<*>
        assertEquals("ขอบคุณ", getField(out[0]!!, "text"))
    }

    @Test fun thaiSuggestionsOrdersByScore() {
        val service = Robolectric.buildService(DroidImeService::class.java).create().get()
        val dict = javaClass.classLoader.let {
            val cls = Class.forName("com.tulipskun.droidime.DroidImeService\$Dictionary")
            val ctor = cls.declaredConstructors.first(); ctor.isAccessible = true
            ctor.newInstance(arrayOf("กิน", "กินข้าว"), intArrayOf(100, 1))
        }
        setField(service, "dictTh", dict)
        val out = call(service, "thaiSuggestions", "กิน") as List<*>
        // The typed word first, then the completion. The strip holds at most three entries.
        assertEquals(2, out.size)
        assertEquals("กิน", getField(out[0]!!, "text"))
        assertEquals("กินข้าว", getField(out[1]!!, "text"))
    }

    @Test fun thaiSuggestionsPrioritizesTheLongestMatchingPrefix() {
        val service = Robolectric.buildService(DroidImeService::class.java).create().get()
        val words = arrayOf("กขคงจ", "กขคงฉ", "กขคจฉ", "กขคชซ", "กขคญฎ", "กขจฉช")
        val frequencies = intArrayOf(1, 1, 900000, 800000, 700000, 600000)
        val cls = Class.forName("com.tulipskun.droidime.DroidImeService\$Dictionary")
        val ctor = cls.declaredConstructors.first(); ctor.isAccessible = true
        val dict = ctor.newInstance(words, frequencies)
        setField(service, "dictTh", dict)
        val out = call(service, "thaiSuggestions", "กขคง") as List<*>
        assertTrue("completion matching the full typed prefix was hidden", out.any {
            getField(it!!, "text") == "กขคงจ" || getField(it!!, "text") == "กขคงฉ"
        })
    }

    @Test fun thaiSuggestionsSupportsWordsLongerThanTwelveCharacters() {
        val service = Robolectric.buildService(DroidImeService::class.java).create().get()
        val word = "กขคงจฉชซญฎฏฐฑฒณ"
        val cls = Class.forName("com.tulipskun.droidime.DroidImeService\$Dictionary")
        val ctor = cls.declaredConstructors.first(); ctor.isAccessible = true
        val dict = ctor.newInstance(arrayOf(word), intArrayOf(100))
        setField(service, "dictTh", dict)
        val out = call(service, "thaiSuggestions", word) as List<*>
        assertTrue("long Thai dictionary word was not offered", out.any { getField(it!!, "text") == word })
    }

    @Test fun englishSuggestionsKeepsTypedWordFirst() {
        val service = Robolectric.buildService(DroidImeService::class.java).create().get()
        val dict = javaClass.classLoader.let {
            val cls = Class.forName("com.tulipskun.droidime.DroidImeService\$Dictionary")
            val ctor = cls.declaredConstructors.first(); ctor.isAccessible = true
            ctor.newInstance(arrayOf("test", "tested", "testing"), intArrayOf(100, 50, 40))
        }
        setField(service, "dictEn", dict)
        val out = call(service, "englishSuggestions", "test") as List<*>
        assertEquals("test", getField(out[0]!!, "text"))
        assertEquals(3, out.size)
    }

    @Test fun englishSuggestionsPutCorrectionAheadOfTypo() {
        val service = Robolectric.buildService(DroidImeService::class.java).create().get()
        val cls = Class.forName("com.tulipskun.droidime.DroidImeService\$Dictionary")
        val ctor = cls.declaredConstructors.first(); ctor.isAccessible = true
        val dict = ctor.newInstance(
            arrayOf("test", "testing", "the"),
            intArrayOf(80, 40, 100)
        )
        setField(service, "dictEn", dict)
        val out = call(service, "englishSuggestions", "teh") as List<*>
        assertEquals("the", getField(out[0]!!, "text"))
        assertEquals("teh", getField(out[out.size - 1]!!, "text"))
    }

    @Test fun englishSuggestionsPredictsLearnedNextWordAfterSpace() {
        val service = Robolectric.buildService(DroidImeService::class.java).create().get()
        val cls = Class.forName("com.tulipskun.droidime.DroidImeService\$Dictionary")
        val ctor = cls.declaredConstructors.first(); ctor.isAccessible = true
        val dict = ctor.newInstance(
            arrayOf("the", "and", "cat", "dog", "hello", "world"),
            intArrayOf(100, 90, 80, 70, 60, 50)
        )
        setField(service, "dictEn", dict)
        call(service, "learnWordTransition", "hello", "world")
        call(service, "learnWordTransition", "hello", "world")
        val out = call(service, "englishSuggestions", "hello hello ") as List<*>
        assertEquals("world", getField(out[0]!!, "text"))
    }

    @Test fun englishSuggestionsFallBackToFrequentWordsAfterSpace() {
        val service = Robolectric.buildService(DroidImeService::class.java).create().get()
        val cls = Class.forName("com.tulipskun.droidime.DroidImeService\$Dictionary")
        val ctor = cls.declaredConstructors.first(); ctor.isAccessible = true
        val dict = ctor.newInstance(arrayOf("the", "and", "cat", "dog"), intArrayOf(100, 90, 80, 70))
        setField(service, "dictEn", dict)
        val out = call(service, "englishSuggestions", "hello ") as List<*>
        assertTrue("frequent next word was not offered", out.any { getField(it!!, "text") == "the" })
        assertFalse("previous word should not be suggested again", out.any { getField(it!!, "text") == "hello" })
    }

    @Test fun glideDictionaryUsesStartAndEndCharacterIndex() {
        val cls = Class.forName("com.tulipskun.droidime.DroidImeService\$Dictionary")
        val ctor = cls.declaredConstructors.first(); ctor.isAccessible = true
        val dict = ctor.newInstance(
            arrayOf("สวัสดี", "ภาษาไทย", "สวัสดิการ"),
            intArrayOf(100, 90, 80)
        )
        val matches = call(dict, "glideCandidates", setOf('ส'), setOf('ี')) as IntArray
        assertTrue(matches.contains(0))
        assertFalse(matches.contains(1))
        assertFalse(matches.contains(2))
    }

    @Test fun glideFindsThaiWordWithShiftLayerCollapsedPath() {
        val service = Robolectric.buildService(DroidImeService::class.java).create().get()
        val dictClass = Class.forName("com.tulipskun.droidime.DroidImeService\$Dictionary")
        val ctor = dictClass.declaredConstructors.first(); ctor.isAccessible = true
        setField(service, "dictTh", ctor.newInstance(arrayOf("ภาษา"), intArrayOf(100)))
        // ษ is the shift layer of า, so "ภาษา" (ภ-า-ษ-า) collapses to two key centres. The gesture
        // detours well away from that straight line, which the old length-ratio filter rejected.
        val centers = mapOf(
            'ภ' to floatArrayOf(0f, 0f),
            'า' to floatArrayOf(120f, 0f),
            'ษ' to floatArrayOf(120f, 0f)
        )
        val xs = floatArrayOf(0f, 20f, 40f, 60f, 80f, 100f, 120f)
        val ys = floatArrayOf(0f, 80f, 120f, 120f, 120f, 80f, 0f)
        val out = call(
            service, "glideCandidates", xs, ys, centers, 40f, true,
            emptyMap<String, Int>()
        ) as List<*>
        assertTrue("collapsed Thai glide should still find ภาษา, got $out", out.contains("ภาษา"))
    }

    @Test fun glidePathHistoryIsSavedAsOneCompleteGesture() {
        val service = Robolectric.buildService(DroidImeService::class.java).create().get()
        val points = listOf(10f to 20f, 30f to 40f, 50f to 60f)
        call(service, "recordGlidePath", points, 100, 100, false)
        val events = call(service, "touchHistory") as List<*>
        assertEquals(3, events.size)
        assertEquals(0, (events[0] as org.json.JSONObject).optInt("index"))
        assertEquals(2, (events[2] as org.json.JSONObject).optInt("index"))
    }

    @Test fun expandedThaiDictionaryIncludesPyThaiNlpCoverage() {
        val service = Robolectric.buildService(DroidImeService::class.java).create().get()
        val thai = call(service, "loadDictionary", "words_th.txt")!!
        for (word in listOf("กฎหมายแรงงาน", "กบเหลาดินสอ", "กรมบังคับคดี", "กฎระเบียบ", "กระดาษเช็ดปาก")) {
            assertTrue("missing Thai word: $word", (call(thai, "indexOf", word) as Int) >= 0)
        }
        assertTrue("Thai dictionary should include the expanded word list",
            (getField(thai, "words") as Array<*>).size >= 72000)
    }

    @Test fun expandedDictionariesContainThaiEnglishSlangAndProfanity() {
        val service = Robolectric.buildService(DroidImeService::class.java).create().get()
        val english = call(service, "loadDictionary", "words_en.txt")!!
        val thai = call(service, "loadDictionary", "words_th.txt")!!
        for (word in listOf("rizz", "delulu", "fuck", "asshole")) {
            assertTrue("missing English word: $word", (call(english, "indexOf", word) as Int) >= 0)
        }
        for (word in listOf("กกช้าง", "กฎหมาย", "กทม.", "กวนตีน", "ควย", "ไอ้สัส", "จึ้ง", "นอยด์", "บ้ง", "ตัวตึง", "ป้ายยา", "มูฟออน", "อีดอก", "อิสัส", "ขิตคาที่", "ดีย์", "ดร.", "กงเกวียนกำเกวียน", "กบเต้นกลางสระบัว", "กระดกกระดนโด่", "เอาสีข้างเข้าถู", "โอ้โลมปฏิโลม")) {
            assertTrue("missing Thai word: $word", (call(thai, "indexOf", word) as Int) >= 0)
        }
        assertTrue((getField(english, "words") as Array<*>).size >= 65000)
        assertTrue((getField(thai, "words") as Array<*>).size >= 54000)
    }
    @Test fun deleteStoresUndoTextAndRestoresIt() {
        val service = Robolectric.buildService(DroidImeService::class.java).create().get()
        val editor = android.widget.EditText(Robolectric.buildActivity(android.app.Activity::class.java).get())
        val connection = BaseInputConnection(editor, true)
        setField(service, "mInputConnection", connection)
        setField(service, "glideCommitted", null)
        connection.commitText("hello", 1)

        call(service, "deleteOne", false)
        assertEquals("o", getField(service, "undoText"))
        assertEquals("hell", connection.getTextBeforeCursor(20, 0).toString())

        call(service, "restoreUndo")
        assertEquals("hello", connection.getTextBeforeCursor(20, 0).toString())
        assertNullValue(getField(service, "undoText"))
    }

    @Test fun removedSuggestionsAreSuppressed() {
        val service = Robolectric.buildService(DroidImeService::class.java).create().get()
        call(service, "removeSuggestion", "hello")
        @Suppress("UNCHECKED_CAST")
        val suppressed = getField(service, "suppressedWords") as Set<String>
        assertTrue(suppressed.contains("hello"))
    }

    private fun assertNullValue(value: Any?) {
        org.junit.Assert.assertNull(value)
    }

    @Test fun repeatedKeyTouchesStayInMemoryWithoutRewritingPreferences() {
        val service = Robolectric.buildService(DroidImeService::class.java).create().get()
        repeat(11) { call(service, "recordKeyTouch", 20f, 30f, 100, 100, null) }
        val events = call(service, "touchHistory") as List<*>
        assertEquals(11, events.size)
    }

    @Test fun deviceEndpointNormalizesDeviceNames() {
        assertEquals("pixel-8-pro", DeviceEndpoint.normalize(" Pixel 8 Pro "))
        assertEquals("android-device", DeviceEndpoint.normalize("---" ).ifBlank { "android-device" })
    }

    @Test fun deviceEndpointRejectsUnsafePathNames() {
        assertTrue(DeviceEndpoint.isValid("pixel-8-pro"))
        assertTrue(DeviceEndpoint.isValid("device01"))
        assertFalse(DeviceEndpoint.isValid("../pixel"))
        assertFalse(DeviceEndpoint.isValid("-pixel"))
        assertFalse(DeviceEndpoint.isValid("pixel/8"))
        assertFalse(DeviceEndpoint.isValid(""))
    }

    @Test fun deviceEndpointUsesSharedHostAndDevicePath() {
        assertEquals("https://mcp.aixodia.dpdns.org/pixel-8", DeviceEndpoint.endpoint("pixel-8"))
        assertEquals("/pixel-8", DeviceEndpoint.path("pixel-8"))
    }

}
