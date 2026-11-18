package com.tulipskun.droidime

import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MainActivityNavigationTest {
    private fun findByText(root: View, text: String): View? {
        if (root is TextView && root.text.toString() == text) return root
        if (root is ViewGroup) {
            for (i in 0 until root.childCount) {
                findByText(root.getChildAt(i), text)?.let { return it }
            }
        }
        return null
    }

    private fun findByDescription(root: View, description: String): View? {
        if (root.contentDescription?.toString() == description) return root
        if (root is ViewGroup) {
            for (i in 0 until root.childCount) {
                findByDescription(root.getChildAt(i), description)?.let { return it }
            }
        }
        return null
    }

    @Test
    fun overviewOpensSeparateKeyboardAndMcpSubscreens() {
        val activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        val decor = activity.window.decorView

        // On the overview neither subscreen's controls are attached.
        assertNull(findByText(decor, "Keyboard setup"))
        assertNull(findByText(decor, "Remote control · MCP"))

        findByDescription(decor, "Keyboard")!!.performClick()
        assertNotNull(findByText(decor, "Keyboard setup"))
        assertNull(findByText(decor, "Remote control · MCP"))

        findByDescription(decor, "MCP")!!.performClick()
        assertNull(findByText(decor, "Keyboard setup"))
        assertNotNull(findByText(decor, "Remote control · MCP"))
    }
}
