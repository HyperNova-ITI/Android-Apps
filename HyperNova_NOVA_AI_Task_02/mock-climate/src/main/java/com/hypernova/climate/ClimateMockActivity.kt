package com.hypernova.climate

import android.app.Activity
import android.content.SharedPreferences
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

class ClimateMockActivity : Activity() {
    private lateinit var modeText: TextView
    private lateinit var statusText: TextView
    private val preferenceListener =
        SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> refresh() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(48, 64, 48, 48)
            setBackgroundColor(Color.rgb(7, 17, 29))

            addView(label("CLIMATE MOCK", 28f, Color.rgb(89, 226, 255)))
            modeText = label("", 18f, Color.WHITE)
            statusText = label("", 20f, Color.LTGRAY)
            addView(modeText)
            addView(statusText)
            addView(label(
                "Select how the next Binder command behaves.\nNormal simulates a confirmed TC397 acknowledgement.",
                15f,
                Color.GRAY,
            ))
            listOf(
                "Normal" to MockMode.NORMAL,
                "Reject" to MockMode.REJECT,
                "Unavailable" to MockMode.UNAVAILABLE,
                "Timeout" to MockMode.TIMEOUT,
            ).forEach { (title, mode) ->
                addView(Button(this@ClimateMockActivity).apply {
                    text = title
                    setOnClickListener {
                        MockMode.set(this@ClimateMockActivity, mode)
                        refresh()
                    }
                }, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            }
        })
    }

    override fun onStart() {
        super.onStart()
        MockMode.preferences(this).registerOnSharedPreferenceChangeListener(preferenceListener)
        refresh()
    }

    override fun onStop() {
        MockMode.preferences(this).unregisterOnSharedPreferenceChangeListener(preferenceListener)
        super.onStop()
    }

    private fun refresh() {
        modeText.text = "Mode: ${MockMode.get(this)}"
        statusText.text = MockMode.status(this)
    }

    private fun label(text: String, size: Float, color: Int) = TextView(this).apply {
        this.text = text
        textSize = size
        setTextColor(color)
        gravity = Gravity.CENTER
        setPadding(8, 16, 8, 16)
    }
}
