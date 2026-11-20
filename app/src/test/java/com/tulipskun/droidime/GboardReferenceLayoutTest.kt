package com.tulipskun.droidime

import android.graphics.Color
import android.view.inputmethod.EditorInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

// Values read from the seven Gboard screenshots supplied for this device.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class GboardReferenceLayoutTest {
    private fun getField(target: Any, name: String): Any? {
        var c: Class<*>? = target.javaClass
        while (c != null) {
            val f = c.declaredFields.firstOrNull { it.name == name }
            if (f != null) { f.isAccessible = true; return f.get(target) }
            c = c.superclass
        }
        throw NoSuchFieldException(name)
    }

    private fun setField(target: Any, name: String, value: Any?) {
        var c: Class<*>? = target.javaClass
        while (c != null) {
            val f = c.declaredFields.firstOrNull { it.name == name }
            if (f != null) { f.isAccessible = true; f.set(target, value); return }
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

    @Test fun darkPaletteMatchesSampledScreenshotPixels() {
        val service = Robolectric.buildService(DroidImeService::class.java).create().get()
        val palette = getField(service, "darkPalette")!!
        fun color(name: String) = palette.javaClass.getDeclaredField(name).let {
            it.isAccessible = true; it.get(palette) as Int
        }
        assertEquals(Color.rgb(31, 31, 31), color("background"))
        assertEquals(Color.rgb(57, 57, 57), color("key"))
        assertEquals(Color.rgb(71, 71, 71), color("special"))
        assertEquals(Color.rgb(226, 226, 226), color("text"))
        assertEquals(Color.rgb(198, 198, 198), color("hint"))
    }

    @Test fun numericPadHasGboardSymbolColumns() {
        val service = Robolectric.buildService(DroidImeService::class.java).create().get()
        @Suppress("UNCHECKED_CAST")
        val rows = getField(service, "padRows") as List<List<String>>
        assertEquals(3, rows.size)
        assertTrue(rows.all { it.size == 5 })
        assertEquals(listOf("+", "1", "2", "3", "%"), rows[0])
        assertEquals(listOf("-", "4", "5", "6", "–"), rows[1])
        assertEquals(listOf("*", "7", "8", "9", "⌫"), rows[2])
    }

    @Test fun searchActionUsesTheMagnifierGlyph() {
        val service = Robolectric.buildService(DroidImeService::class.java).create().get()
        val info = EditorInfo().apply { imeOptions = EditorInfo.IME_ACTION_SEARCH }
        setField(service, "mInputEditorInfo", info)
        assertEquals("Search", call(service, "enterLabel"))
        val glyphs = checkNotNull(Class.forName("com.tulipskun.droidime.DroidImeService\$KeyGlyph").enumConstants)
        assertTrue(glyphs.any { (it as Enum<*>).name == "SEARCH" })
    }
}
