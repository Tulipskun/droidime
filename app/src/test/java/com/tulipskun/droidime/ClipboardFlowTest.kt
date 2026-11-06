package com.tulipskun.droidime

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ClipboardFlowTest {
    private fun getField(target: Any, name: String): Any? {
        var c: Class<*>? = target.javaClass
        while (c != null) {
            val f = c.declaredFields.firstOrNull { it.name == name }
            if (f != null) { f.isAccessible = true; return f.get(target) }
            c = c.superclass
        }
        throw NoSuchFieldException(name)
    }

    @Test fun backgroundServiceDoesNotReadClipboard() {
        val service = Robolectric.buildService(DroidImeService::class.java).create().get()
        val manager = service.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        manager.setPrimaryClip(ClipData.newPlainText("test", "private background copy"))
        val clips = getField(service, "clips") as List<*>
        assertTrue("clipboard must not be captured while the IME is hidden", clips.isEmpty())
        assertFalse(getField(service, "clipboardListening") as Boolean)
    }

    @Test fun openingKeyboardCapturesExistingClip() {
        val service = Robolectric.buildService(DroidImeService::class.java).create().get()
        val manager = service.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        manager.setPrimaryClip(ClipData.newPlainText("test", "already copied"))
        service.onStartInputView(null, false)
        val clips = getField(service, "clips") as List<*>
        assertTrue("expected the existing clip after the IME became visible", clips.isNotEmpty())
        assertEquals("already copied", getField(clips[0]!!, "text"))
    }

    @Test fun clipChangeWhileShowingIsRecorded() {
        val service = Robolectric.buildService(DroidImeService::class.java).create().get()
        val manager = service.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        service.onStartInputView(null, false)
        manager.setPrimaryClip(ClipData.newPlainText("test", "fresh copy"))
        val clips = getField(service, "clips") as List<*>
        assertTrue("expected the new clip to be recorded while visible", clips.isNotEmpty())
        assertEquals("fresh copy", getField(clips[0]!!, "text"))
    }

    @Test fun hidingKeyboardStopsClipboardCapture() {
        val service = Robolectric.buildService(DroidImeService::class.java).create().get()
        val manager = service.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        service.onStartInputView(null, false)
        service.onFinishInputView(true)
        manager.setPrimaryClip(ClipData.newPlainText("test", "copy after close"))
        val clips = getField(service, "clips") as List<*>
        assertTrue("clipboard must stop being captured when the IME closes", clips.isEmpty())
        assertFalse(getField(service, "clipboardListening") as Boolean)
    }
}
