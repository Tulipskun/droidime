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
}
