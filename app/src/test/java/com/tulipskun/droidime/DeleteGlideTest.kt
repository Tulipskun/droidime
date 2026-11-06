package com.tulipskun.droidime

import android.view.inputmethod.BaseInputConnection
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DeleteGlideTest {
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

    // A glide word is committed as the text before the cursor; delete must take the whole word
    // back, not just its last character.
    @Test fun deleteRemovesWholeGlideWord() {
        val service = Robolectric.buildService(DroidImeService::class.java).create().get()
        val editor = android.widget.EditText(Robolectric.buildActivity(android.app.Activity::class.java).get())
        val connection = BaseInputConnection(editor, true)
        // The IME reads its connection from the framework; inject a stub one the same way.
        setField(service, "mInputConnection", connection)
        setField(service, "glideCommitted", "hello ")
        setField(service, "glideAlternatives", emptyList<Any>())
        connection.commitText("hello ", 1)
        call(service, "deleteOne", false)
        assertEquals("", connection.getTextBeforeCursor(20, 0).toString())
    }

    // With no glide word pending, delete behaves as before: one code point.
    @Test fun deleteWithoutGlideRemovesOneCharacter() {
        val service = Robolectric.buildService(DroidImeService::class.java).create().get()
        val editor = android.widget.EditText(Robolectric.buildActivity(android.app.Activity::class.java).get())
        val connection = BaseInputConnection(editor, true)
        // The IME reads its connection from the framework; inject a stub one the same way.
        setField(service, "mInputConnection", connection)
        setField(service, "glideCommitted", null)
        connection.commitText("hello", 1)
        call(service, "deleteOne", false)
        assertEquals("hell", connection.getTextBeforeCursor(20, 0).toString())
    }
    @Test fun deleteKeepsThaiComposingRegionAndDoesNotRestoreStaleText() {
        val service = Robolectric.buildService(DroidImeService::class.java).create().get()
        val editor = android.widget.EditText(Robolectric.buildActivity(android.app.Activity::class.java).get())
        val connection = BaseInputConnection(editor, true)
        setField(service, "mInputConnection", connection)
        setField(service, "glideCommitted", null)
        connection.setComposingText("ก้ข", 1)
        setField(service, "thaiComposingText", "ก้ข")

        call(service, "deleteOne", false)
        assertEquals("ก้", connection.getTextBeforeCursor(20, 0).toString())
        assertEquals("ก้", getField(service, "thaiComposingText"))

        call(service, "deleteOne", false)
        assertEquals("ก", connection.getTextBeforeCursor(20, 0).toString())
        assertEquals("ก", getField(service, "thaiComposingText"))

        call(service, "deleteOne", false)
        assertEquals("", connection.getTextBeforeCursor(20, 0).toString())
        assertEquals("", getField(service, "thaiComposingText"))
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

}
