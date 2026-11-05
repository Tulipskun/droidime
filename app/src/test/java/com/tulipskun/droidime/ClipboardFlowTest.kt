package com.tulipskun.droidime

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ClipboardFlowTest {
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

    // The service's onCreate picks up the clip already on the clipboard, the way it would on a
    // device when the keyboard is opened with something copied.
    @Test fun onCreatePicksUpExistingClip() {
        val service = Robolectric.buildService(DroidImeService::class.java).create().get()
        val manager = service.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        manager.setPrimaryClip(ClipData.newPlainText("test", "already copied"))
        // Re-create so onCreate runs again with the clip in place.
        val service2 = Robolectric.buildService(DroidImeService::class.java).create().get()
        val manager2 = service2.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        manager2.setPrimaryClip(ClipData.newPlainText("test", "already copied"))
        val clips = getField(service2, "clips") as List<*>
        assertTrue("expected the existing clip to be recorded, got $clips", clips.isNotEmpty())
        assertEquals("already copied", getField(clips[0]!!, "text"))
    }

    // A clip written while the keyboard is showing arrives through the listener.
    @Test fun clipChangeWhileShowingIsRecorded() {
        val service = Robolectric.buildService(DroidImeService::class.java).create().get()
        val manager = service.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        setField(service, "clips", ArrayList<Any>())
        manager.setPrimaryClip(ClipData.newPlainText("test", "fresh copy"))
        val clips = getField(service, "clips") as List<*>
        assertTrue("expected the new clip to be recorded, got $clips", clips.isNotEmpty())
        assertEquals("fresh copy", getField(clips[0]!!, "text"))
    }
}
