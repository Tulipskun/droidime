package com.tulipskun.droidime

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
}
