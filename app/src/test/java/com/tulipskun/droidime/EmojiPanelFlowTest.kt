package com.tulipskun.droidime

import android.app.AlertDialog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAlertDialog

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class EmojiPanelFlowTest {
    private fun invoke(target: Any, name: String, vararg args: Any?): Any? {
        val method = target.javaClass.declaredMethods.first { it.name == name && it.parameterCount == args.size }
        method.isAccessible = true
        return method.invoke(target, *args)
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

    @Test fun recentEmojiAreStoredForTheNextSession() {
        val service = Robolectric.buildService(DroidImeService::class.java).create().get()
        invoke(service, "insertEmoji", "😀")
        val recent = getField(service, "recentEmojis") as List<*>
        assertEquals("😀", recent.first())
        assertTrue(service.getSharedPreferences("keyboard_settings", 0)
            .getString("recent_emojis", "").orEmpty().contains("😀"))
    }

    @Test fun emojiSearchDialogOpensFromIme() {
        val service = Robolectric.buildService(DroidImeService::class.java).create().get()
        invoke(service, "showEmojiSearch")
        val dialog = ShadowAlertDialog.getLatestAlertDialog()
        assertNotNull(dialog)
        assertTrue(dialog.isShowing)
    }

    @Test fun emojiSearchMatchesUnicodeNames() {
        val service = Robolectric.buildService(DroidImeService::class.java).create().get()
        assertEquals(true, invoke(service, "emojiMatchesQuery", "😀", "grinning"))
        assertEquals(true, invoke(service, "emojiMatchesQuery", "🍕", "pizza"))
    }
}
