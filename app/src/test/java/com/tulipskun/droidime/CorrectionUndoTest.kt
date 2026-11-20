package com.tulipskun.droidime

import android.view.inputmethod.BaseInputConnection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CorrectionUndoTest {
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

    private fun suggestion(text: String, replaceLen: Int, append: String): Any {
        val cls = Class.forName("com.tulipskun.droidime.DroidImeService\$Suggestion")
        val ctor = cls.declaredConstructors.first()
        ctor.isAccessible = true
        return ctor.newInstance(text, replaceLen, append, 0)
    }

    private fun serviceWithText(text: String): Pair<DroidImeService, BaseInputConnection> {
        val service = Robolectric.buildService(DroidImeService::class.java).create().get()
        val editor = android.widget.EditText(Robolectric.buildActivity(android.app.Activity::class.java).get())
        val connection = BaseInputConnection(editor, true)
        setField(service, "mInputConnection", connection)
        setField(service, "glideCommitted", null)
        connection.commitText(text, 1)
        return service to connection
    }

    @Test fun acceptingACorrectionOffersOneUndo() {
        val (service, connection) = serviceWithText("teh")
        call(service, "applySuggestion", suggestion("the", 3, " "))
        assertEquals("the ", connection.getTextBeforeCursor(20, 0).toString())
        assertNotNull(getField(service, "correctionUndo"))

        call(service, "undoCorrection")
        assertEquals("teh", connection.getTextBeforeCursor(20, 0).toString())
        assertNull(getField(service, "correctionUndo"))
    }

    @Test fun acceptingTheLiteralWordDoesNotOfferUndo() {
        val (service, connection) = serviceWithText("hello")
        call(service, "applySuggestion", suggestion("hello", 5, " "))
        assertNull(getField(service, "correctionUndo"))
        assertEquals("hello ", connection.getTextBeforeCursor(20, 0).toString())
    }

    @Test fun typingDismissesThePendingUndo() {
        val (service, _) = serviceWithText("teh")
        call(service, "applySuggestion", suggestion("the", 3, " "))
        assertNotNull(getField(service, "correctionUndo"))
        call(service, "typeText", "x")
        assertNull(getField(service, "correctionUndo"))
    }
}
