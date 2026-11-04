package com.tulipskun.droidime

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

class MainActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(28, 32, 28, 32)
            setBackgroundColor(0xFFF4F5F8.toInt())
        }

        root.addView(TextView(this).apply {
            text = getString(R.string.setup_title)
            textSize = 30f
            setTextColor(0xFF182033.toInt())
            gravity = Gravity.CENTER
        })

        root.addView(TextView(this).apply {
            text = getString(R.string.setup_message)
            textSize = 16f
            setTextColor(0xFF596174.toInt())
            gravity = Gravity.CENTER
            setPadding(0, 16, 0, 24)
        })

        root.addView(Button(this).apply {
            text = getString(R.string.open_settings)
            setOnClickListener { startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS)) }
        })

        root.addView(Button(this).apply {
            text = getString(R.string.select_keyboard)
            setOnClickListener {
                val imm = getSystemService(INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
                imm.showInputMethodPicker()
            }
        })

        setContentView(root)
    }
}
