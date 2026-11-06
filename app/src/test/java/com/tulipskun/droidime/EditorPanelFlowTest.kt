package com.tulipskun.droidime

import android.app.Activity
import android.content.Context
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.widget.EditText
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
class EditorPanelFlowTest {
    private fun setField(target: Any, name: String, value: Any?) {
        var type: Class<*>? = target.javaClass
        while (type != null) {
            val field = type.declaredFields.firstOrNull { it.name == name }
            if (field != null) {
                field.isAccessible = true
                field.set(target, value)
                return
            }
            type = type.superclass
        }
        throw NoSuchFieldException(name)
    }

    private fun getField(target: Any, name: String): Any? {
        var type: Class<*>? = target.javaClass
        while (type != null) {
            val field = type.declaredFields.firstOrNull { it.name == name }
            if (field != null) {
                field.isAccessible = true
                return field.get(target)
            }
            type = type.superclass
        }
        throw NoSuchFieldException(name)
    }

    private fun invoke(target: Any, name: String, vararg args: Any?): Any? {
        val method = target.javaClass.declaredMethods.first {
            it.name == name && it.parameterCount == args.size
        }
        method.isAccessible = true
        return method.invoke(target, *args)
    }

    private class RecordingInputConnection(target: View) : BaseInputConnection(target, true) {
        val events = ArrayList<KeyEvent>()
        override fun sendKeyEvent(event: KeyEvent): Boolean {
            events.add(KeyEvent(event))
            return true
        }
    }

    private fun editor(text: String = "hello"): Triple<DroidImeService, EditText, android.view.inputmethod.InputConnection> {
        val service = Robolectric.buildService(DroidImeService::class.java).create().get()
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val view = EditText(activity).apply {
            setText(text)
            setSelection(text.length)
        }
        val connection = view.onCreateInputConnection(EditorInfo())
            ?: throw AssertionError("EditText did not create an InputConnection")
        setField(service, "mInputConnection", connection)
        return Triple(service, view, connection)
    }

    @Test fun arrowKeysForwardDirectionalKeyEvents() {
        val service = Robolectric.buildService(DroidImeService::class.java).create().get()
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val view = EditText(activity).apply { setText("hello") }
        val connection = RecordingInputConnection(view)
        setField(service, "mInputConnection", connection)

        invoke(service, "editorAction", "←")
        invoke(service, "editorAction", "→")

        val pressed = connection.events.filter { it.action == KeyEvent.ACTION_DOWN }.map { it.keyCode }
        assertEquals(listOf(KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT), pressed)
    }

    @Test fun selectModeAddsShiftModifierToArrowEvents() {
        val service = Robolectric.buildService(DroidImeService::class.java).create().get()
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val view = EditText(activity).apply { setText("hello") }
        val connection = RecordingInputConnection(view)
        setField(service, "mInputConnection", connection)

        invoke(service, "editorAction", "Select")
        assertTrue(getField(service, "editorSelecting") as Boolean)
        invoke(service, "editorAction", "←")
        val leftDown = connection.events.first { it.action == KeyEvent.ACTION_DOWN }
        assertTrue(leftDown.metaState and KeyEvent.META_SHIFT_ON != 0)

        invoke(service, "editorAction", "Select")
        assertFalse(getField(service, "editorSelecting") as Boolean)
    }

    @Test fun selectAllSelectsTheWholeText() {
        val (service, view) = editor()
        invoke(service, "editorAction", "Select all")
        assertEquals(0, view.selectionStart)
        assertEquals(5, view.selectionEnd)
    }

    @Test fun copyActionWritesSelectedTextToClipboard() {
        val (service, view) = editor()
        invoke(service, "editorAction", "Select all")
        invoke(service, "editorAction", "Copy")
        val manager = service.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        assertEquals("hello", manager.primaryClip?.getItemAt(0)?.text?.toString())
        assertEquals(0, view.selectionStart)
        assertEquals(5, view.selectionEnd)
    }
}
