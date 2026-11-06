package com.tulipskun.droidime

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import com.tulipskun.droidime.KeyboardRenderTest.Companion.setField
import java.io.File
import java.io.OutputStream
import java.util.zip.CRC32
import java.util.zip.Deflater
import java.util.zip.DeflaterOutputStream
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class KeyboardRenderTest {

    private val width = 1080
    private val height = 640
    private val outDir = File("build/render").apply { mkdirs() }

    @Test
    fun renderStates() {
        val service = Robolectric.buildService(DroidImeService::class.java).create().get()

        service.getSharedPreferences("keyboard_settings", android.content.Context.MODE_PRIVATE)
            .edit()
            .putString("theme", "light")
            .putBoolean("number_row_en", true)
            .putBoolean("number_row_th", false)
            .putBoolean("key_hints", true)
            .apply()

        // Build the Canvas keyboard view directly so no IME window is needed.
        val canvasClass = Class.forName("com.tulipskun.droidime.DroidImeService\$KeyboardCanvasView")
        val ctor = canvasClass.declaredConstructors.first { it.parameterCount == 2 }
        ctor.isAccessible = true
        val view = ctor.newInstance(service, service) as View
        service.setField("keyboardView", view)
        val rebuild = canvasClass.getDeclaredMethod("rebuild").apply { isAccessible = true }

        view.measure(
            View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY)
        )
        view.layout(0, 0, width, height)

        val language = Class.forName("com.tulipskun.droidime.DroidImeService\$Language")
        val fieldKind = Class.forName("com.tulipskun.droidime.DroidImeService\$FieldKind")

        data class State(
            val file: String,
            val lang: String,
            val shifted: Boolean,
            val symbols: Boolean,
            val symbolPage: Int,
            val numberPad: Boolean,
            val clip: Boolean = false,
            val clipMenu: Boolean = false,
            val clipEdit: Boolean = false,
            val editor: Boolean = false
        )

        val states = listOf(
            State("en_normal", "ENGLISH", false, false, 0, false),
            State("en_shift", "ENGLISH", true, false, 0, false),
            State("en_symbols", "ENGLISH", false, true, 0, false),
            State("en_more", "ENGLISH", false, true, 1, false),
            State("en_pad", "ENGLISH", false, false, 0, true),
            State("th_normal", "THAI", false, false, 0, false),
            State("clip_empty", "ENGLISH", false, false, 0, false, clip = true),
            State("clip_items", "ENGLISH", false, false, 0, false, clip = true),
            State("clip_menu", "ENGLISH", false, false, 0, false, clip = true, clipMenu = true),
            State("clip_edit", "ENGLISH", false, false, 0, false, clip = true, clipEdit = true),
            State("text_editing", "ENGLISH", false, false, 0, false, editor = true)
        )

        for (s in states) {
            service.setField("language", enumOf(language, s.lang))
            service.setField("fieldKind", enumOf(fieldKind, "TEXT"))
            service.setField("shifted", s.shifted)
            service.setField("shiftLocked", false)
            service.setField("symbols", s.symbols)
            service.setField("symbolPage", s.symbolPage)
            service.setField("numberPad", s.numberPad)
            service.setField("navigationInset", 0)
            if (s.file == "clip_items" || s.file == "clip_menu" || s.file == "clip_edit") {
                service.setField("clips", ArrayList(listOf(
                    service.clip("1", "hello world", true),
                    service.clip("2", "second clip", false),
                    service.clip("3", "third clip", false)
                )))
            } else {
                service.setField("clips", ArrayList<Any>())
            }
            service.setField("clipPanelVisible", s.clip)
            service.setField("editorPanelVisible", s.editor)
            service.setField("clipMenuId", if (s.clipMenu) "2" else null)
            service.setField("clipSelection", null)
            service.setField("clipEdit", s.clipEdit)

            rebuild.invoke(view)

            val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            view.draw(Canvas(bmp))

            val pixels = IntArray(width * height)
            bmp.getPixels(pixels, 0, width, 0, 0, width, height)
            // A blank keyboard (the zero-height bug) renders as one flat colour.
            val distinct = pixels.toHashSet().size
            org.junit.Assert.assertTrue(
                "${s.file} rendered blank ($distinct distinct colours)",
                distinct > 8
            )
            writePng(File(outDir, "${s.file}.png"), width, height, pixels)
        }

        val emojiCategory = Class.forName("com.tulipskun.droidime.DroidImeService\$EmojiCategory")
        service.setField("clipPanelVisible", false)
        service.setField("emojiPanelVisible", true)
        for (category in emojiCategory.enumConstants!!) {
            service.setField("emojiCategory", category)
            rebuild.invoke(view)
            val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            view.draw(Canvas(bmp))
            val pixels = IntArray(width * height)
            bmp.getPixels(pixels, 0, width, 0, 0, width, height)
            val distinct = pixels.toHashSet().size
            org.junit.Assert.assertTrue("emoji ${(category as Enum<*>).name} rendered blank", distinct > 8)
            writePng(File(outDir, "emoji_${(category as Enum<*>).name.lowercase()}.png"), width, height, pixels)
        }
    }

    private fun enumOf(cls: Class<*>, name: String): Any =
        cls.enumConstants!!.first { (it as Enum<*>).name == name }

    // Minimal RGBA PNG writer using only java.util.zip (android.jar has no ImageIO).
    private fun writePng(file: File, w: Int, h: Int, argb: IntArray) {
        val raw = ByteArray(h * (1 + w * 4))
        var p = 0
        for (y in 0 until h) {
            raw[p++] = 0 // filter: none
            for (x in 0 until w) {
                val c = argb[y * w + x]
                raw[p++] = (c shr 16 and 0xFF).toByte() // R
                raw[p++] = (c shr 8 and 0xFF).toByte()  // G
                raw[p++] = (c and 0xFF).toByte()        // B
                raw[p++] = (c ushr 24 and 0xFF).toByte()// A
            }
        }
        val idat = java.io.ByteArrayOutputStream().also { out ->
            DeflaterOutputStream(out, Deflater(Deflater.DEFAULT_COMPRESSION)).use { it.write(raw) }
        }.toByteArray()

        file.outputStream().use { out ->
            out.write(byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10))
            chunk(out, "IHDR", intArrayToBytes(intArrayOf(w, h)) + byteArrayOf(8, 6, 0, 0, 0))
            chunk(out, "IDAT", idat)
            chunk(out, "IEND", ByteArray(0))
        }
    }

    private fun intArrayToBytes(values: IntArray): ByteArray {
        val b = ByteArray(values.size * 4)
        for (i in values.indices) {
            b[i * 4] = (values[i] ushr 24).toByte()
            b[i * 4 + 1] = (values[i] ushr 16).toByte()
            b[i * 4 + 2] = (values[i] ushr 8).toByte()
            b[i * 4 + 3] = (values[i]).toByte()
        }
        return b
    }

    private fun chunk(out: OutputStream, type: String, data: ByteArray) {
        out.write(intArrayToBytes(intArrayOf(data.size)))
        val typeBytes = type.toByteArray(Charsets.ISO_8859_1)
        out.write(typeBytes)
        out.write(data)
        val crc = CRC32()
        crc.update(typeBytes)
        crc.update(data)
        out.write(intArrayToBytes(intArrayOf(crc.value.toInt())))
    }

    companion object {
        // The service keeps its state in a nested object, so the field may live on a superclass of
        // the runtime class; walk up until it is found rather than assuming the first match.
        fun Any.setField(name: String, value: Any?) {
            val field = findField(javaClass, name)
                ?: throw NoSuchFieldException("${javaClass.name}.$name")
            field.isAccessible = true
            field.set(this, value)
        }

        private fun findField(cls: Class<*>?, name: String): java.lang.reflect.Field? {
            var current = cls
            while (current != null) {
                current.declaredFields.firstOrNull { it.name == name }?.let { return it }
                current = current.superclass
            }
            return null
        }

        // A test clip, built through the same private class the service uses.
        fun DroidImeService.clip(id: String, text: String, pinned: Boolean): Any {
            val cls = Class.forName("com.tulipskun.droidime.DroidImeService\$Clip")
            val ctor = cls.declaredConstructors.first()
            ctor.isAccessible = true
            return ctor.newInstance(id, text, pinned, 0L)
        }
    }
}
